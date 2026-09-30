#include "uvc_bulk_stream.h"
#include <cerrno>
#include <cstring>
#include <poll.h>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>
#include <algorithm>
#include <chrono>

namespace stream4k60 {
UvcBulkStream::UvcBulkStream(int fd,uint8_t endpoint,size_t packetBytes,size_t transferBytes,int urbCount,jobject callback,jmethodID frameMethod,JavaVM* vm)
:fd_(fd),endpoint_(endpoint),packetBytes_(std::max<size_t>(packetBytes,64)),transferBytes_(std::max<size_t>(transferBytes,packetBytes_*16)),urbCount_(std::clamp(urbCount,4,32)),callback_(callback),frameMethod_(frameMethod),vm_(vm){}
UvcBulkStream::~UvcBulkStream(){stop();if(callback_&&vm_){JNIEnv*e=nullptr;bool a=false;if(vm_->GetEnv(reinterpret_cast<void**>(&e),JNI_VERSION_1_6)!=JNI_OK){if(vm_->AttachCurrentThread(&e,nullptr)==JNI_OK)a=true;}if(e)e->DeleteGlobalRef(callback_);if(a)vm_->DetachCurrentThread();callback_=nullptr;}}
bool UvcBulkStream::start(){if(running_.exchange(true))return false;if(fd_<0||!callback_||!frameMethod_||!vm_){running_=false;return false;}slots_.reserve(urbCount_);const size_t bytes=sizeof(usbfs::Urb);for(int i=0;i<urbCount_;++i){auto s=std::make_unique<Slot>();s->urbMemorySize=bytes;s->urbMemory=(uint8_t*)calloc(1,bytes);if(!s->urbMemory){running_=false;return false;}s->urb=(usbfs::Urb*)s->urbMemory;s->storage.resize(transferBytes_);s->urb->type=usbfs::URB_TYPE_BULK;s->urb->endpoint=endpoint_;s->urb->flags=0;s->urb->buffer=s->storage.data();s->urb->buffer_length=(int)s->storage.size();s->urb->number_of_packets=0;s->urb->usercontext=s.get();slots_.push_back(std::move(s));}for(auto&s:slots_)if(!submit(*s)){stop();return false;}frame_.reserve(4*1024*1024);thread_=std::thread(&UvcBulkStream::reapLoop,this);callbackThread_=std::thread(&UvcBulkStream::callbackLoop,this);return true;}
bool UvcBulkStream::submit(Slot&s){s.urb->status=0;s.urb->actual_length=0;s.urb->error_count=0;return ioctl(fd_,USBDEVFS_SUBMITURB,s.urb)==0;}
void UvcBulkStream::discard(Slot&s){if(s.urb)ioctl(fd_,USBDEVFS_DISCARDURB,s.urb);}
void UvcBulkStream::stop(){const bool wasRunning=running_.exchange(false);if(wasRunning)for(auto&s:slots_)discard(*s);frameQueueCv_.notify_all();if(thread_.joinable())thread_.join();if(callbackThread_.joinable())callbackThread_.join();for(auto&s:slots_){free(s->urbMemory);s->urb=nullptr;s->urbMemory=nullptr;}slots_.clear();frame_.clear();{std::lock_guard<std::mutex> lock(frameQueueMutex_);frameQueue_.clear();}currentFid_=-1;}
uint64_t UvcBulkStream::monotonicUs(){timespec ts{};clock_gettime(CLOCK_MONOTONIC,&ts);return (uint64_t)ts.tv_sec*1000000ull+(uint64_t)ts.tv_nsec/1000ull;}
void UvcBulkStream::reapLoop(){JNIEnv*e=nullptr;bool a=false;if(vm_->GetEnv(reinterpret_cast<void**>(&e),JNI_VERSION_1_6)!=JNI_OK){if(vm_->AttachCurrentThread(&e,nullptr)!=JNI_OK){running_=false;return;}a=true;}while(running_){void*ctx=nullptr;int rc=ioctl(fd_,USBDEVFS_REAPURBNDELAY,&ctx);if(rc<0){if(errno==EAGAIN||errno==EINTR){pollfd p{fd_,POLLIN,0};poll(&p,1,2);continue;}running_=false;break;}auto*s=(Slot*)ctx;if(!s)continue;processUrb(s);if(running_&&!submit(*s)){running_=false;break;}}if(a)vm_->DetachCurrentThread();}
void UvcBulkStream::processUrb(Slot*s){if(s->urb->status!=0){transferErrors_++;sawError_=true;}if(s->urb->actual_length>0)processBytes(s->storage.data(),(size_t)s->urb->actual_length);}
void UvcBulkStream::processBytes(const uint8_t* d,size_t n){
    size_t p=0;
    while(p<n){
        const size_t chunk=std::min(packetBytes_, n-p);
        if(chunk<2){ break; }
        const uint8_t h=d[p];
        const uint8_t f=d[p+1];
        if(h<2 || h>chunk){
            // Some devices coalesce short packets. Advance one packet boundary rather than
            // turning the whole transfer into a corrupt frame.
            p += chunk;
            sawError_=true;
            transferErrors_++;
            continue;
        }
        const int fid=f&1;
        if(currentFid_<0) currentFid_=fid;
        if(fid!=currentFid_){
            if(!frame_.empty()) emitFrame();
            frame_.clear();
            currentFid_=fid;
            sawError_=false;
        }
        const size_t payload=chunk-h;
        constexpr size_t MAX=32ull*1024*1024;
        if(payload){
            if(frame_.size()+payload>MAX){
                frame_.clear();
                droppedFrames_++;
                sawError_=true;
            } else {
                frame_.insert(frame_.end(),d+h,d+chunk);
            }
        }
        if(f&0x02){
            if(!frame_.empty()) emitFrame();
            frame_.clear();
        }
        p+=chunk;
        // A short USB packet terminates a bulk transaction. Do not interpret following
        // bytes as another UVC payload if the controller returned a short final packet.
        if(chunk<packetBytes_) break;
    }
}
void UvcBulkStream::emitFrame(){
    if(frame_.empty())return;
    QueuedFrame frame;
    frame.data=std::move(frame_);
    frame.ptsUs=monotonicUs();
    frame.hadError=sawError_;
    frame_.clear();
    frame_.reserve(4*1024*1024);
    {
        std::lock_guard<std::mutex> lock(frameQueueMutex_);
        constexpr size_t kMaxQueuedFrames=4;
        if(frameQueue_.size()>=kMaxQueuedFrames){frameQueue_.pop_front();droppedFrames_++;}
        frameQueue_.push_back(std::move(frame));
    }
    frameQueueCv_.notify_one();
    sawError_=false;
}
void UvcBulkStream::callbackLoop(){
    JNIEnv*e=nullptr;bool a=false;
    if(vm_->GetEnv(reinterpret_cast<void**>(&e),JNI_VERSION_1_6)!=JNI_OK){if(vm_->AttachCurrentThread(&e,nullptr)!=JNI_OK)return;a=true;}
    while(running_){
        QueuedFrame frame;
        {std::unique_lock<std::mutex> lock(frameQueueMutex_);frameQueueCv_.wait_for(lock,std::chrono::milliseconds(10),[&]{return !frameQueue_.empty()||!running_;});if(frameQueue_.empty()){if(!running_)break;else continue;}frame=std::move(frameQueue_.front());frameQueue_.pop_front();}
        jobject direct=e->NewDirectByteBuffer(frame.data.data(),(jlong)frame.data.size());
        if(direct){e->CallVoidMethod(callback_,frameMethod_,direct,(jlong)frame.ptsUs,(jint)(frame.hadError?1:0));e->DeleteLocalRef(direct);}
        if(e->ExceptionCheck()){e->ExceptionClear();droppedFrames_++;}else frames_++;
    }
    if(a)vm_->DetachCurrentThread();
}

}
