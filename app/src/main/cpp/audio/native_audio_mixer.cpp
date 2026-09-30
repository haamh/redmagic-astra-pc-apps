#include "native_audio_mixer.h"
#include <algorithm>
#include <chrono>
#include <cmath>
#include <time.h>

namespace stream4k60 {
namespace {
static uint64_t monoUs() { timespec ts{}; clock_gettime(CLOCK_MONOTONIC, &ts); return static_cast<uint64_t>(ts.tv_sec)*1000000ull + static_cast<uint64_t>(ts.tv_nsec)/1000ull; }
static float clamp1(float v) { return std::max(-1.0f, std::min(1.0f, v)); }
}

NativeAudioMixer::Ring::Ring(size_t cap, int sr) : data(cap * 2, 0.0f), capacityFrames(std::max<size_t>(2, cap)), sampleRate(std::max(8000, sr)) {}

bool NativeAudioMixer::Ring::push(const float* src, size_t frames, int ch, uint64_t ptsUs) {
    if (!src || !frames) return true;
    channels = ch;
    const size_t h=head.load(std::memory_order_relaxed), t=tail.load(std::memory_order_acquire);
    const size_t used = h>=t ? h-t : capacityFrames-(t-h);
    const size_t free = capacityFrames-1-used;
    if (frames>free) return false;
    if (used==0) startPts.store(ptsUs,std::memory_order_release);
    for(size_t f=0;f<frames;++f){size_t idx=(h+f)%capacityFrames;if(ch==1){data[idx*2]=src[f];data[idx*2+1]=src[f];}else{data[idx*2]=src[f*ch];data[idx*2+1]=src[f*ch+1];}}
    head.store((h+frames)%capacityFrames,std::memory_order_release);return true;
}
size_t NativeAudioMixer::Ring::availableFrames()const{size_t h=head.load(std::memory_order_acquire),t=tail.load(std::memory_order_acquire);return h>=t?h-t:capacityFrames-(t-h);}
size_t NativeAudioMixer::Ring::discard(size_t frames){
    const size_t available=availableFrames();
    const size_t n=std::min(frames,available);
    if(n==0)return 0;
    size_t t=tail.load(std::memory_order_relaxed);
    t=(t+n)%capacityFrames;
    tail.store(t,std::memory_order_release);
    const size_t left=available-n;
    if(left==0)startPts.store(0,std::memory_order_release);
    return n;
}
bool NativeAudioMixer::Ring::pop(float*dst,size_t frames){if(!dst||availableFrames()<frames)return false;size_t t=tail.load(std::memory_order_relaxed);for(size_t f=0;f<frames;++f){size_t idx=(t+f)%capacityFrames;dst[f*2]=data[idx*2];dst[f*2+1]=data[idx*2+1];}tail.store((t+frames)%capacityFrames,std::memory_order_release);return true;}
uint64_t NativeAudioMixer::Ring::startPtsUs()const{return startPts.load(std::memory_order_acquire);}void NativeAudioMixer::Ring::clear(){head.store(0);tail.store(0);startPts.store(0);}

NativeAudioMixer::NativeAudioMixer(JavaVM* vm,jobject callback,int sr,int ch,int block,int monitorDevice,bool monitorEnabled)
:vm_(vm),sampleRate_(std::max(8000,sr)),channels_(ch==1?1:2),blockFrames_(std::max(48,block)),monitorDeviceId_(monitorDevice),monitorEnabled_(monitorEnabled){
    JNIEnv* env=nullptr;if(vm_&&vm_->GetEnv(reinterpret_cast<void**>(&env),JNI_VERSION_1_6)==JNI_OK&&callback){callback_=env->NewGlobalRef(callback);jclass cls=env->GetObjectClass(callback_);if(cls)callbackMethod_=env->GetMethodID(cls,"onMixed","(Ljava/nio/ByteBuffer;JIII)V");}
}
NativeAudioMixer::~NativeAudioMixer(){stop();if(vm_&&callback_){JNIEnv* env=nullptr;bool a=false;if(vm_->GetEnv(reinterpret_cast<void**>(&env),JNI_VERSION_1_6)!=JNI_OK){if(vm_->AttachCurrentThread(&env,nullptr)==JNI_OK)a=true;}if(env)env->DeleteGlobalRef(callback_);if(a)vm_->DetachCurrentThread();callback_=nullptr;callbackMethod_=nullptr;}}

bool NativeAudioMixer::start(){if(running_.exchange(true))return false;{std::lock_guard<std::mutex>l(inputsMutex_);for(auto&i:inputs_){if(!openInput(*i)){running_=false;for(auto&j:inputs_)closeInput(*j);return false;}}}if(monitorEnabled_.load()){monitorRing_=std::make_unique<Ring>(static_cast<size_t>(sampleRate_), sampleRate_);if(!openMonitor()){running_=false;for(auto&i:inputs_)closeInput(*i);return false;}}mixThread_=std::thread(&NativeAudioMixer::mixLoop,this);return true;}

bool NativeAudioMixer::addInput(const std::string& id,int32_t deviceId,float volume,float balance,bool muted,int monitoring,int syncOffsetMs){std::lock_guard<std::mutex>l(inputsMutex_);if(std::any_of(inputs_.begin(),inputs_.end(),[&](const auto&i){return i->id==id;}))return true;auto in=std::make_unique<Input>();in->id=id;in->deviceId=deviceId;in->volume=std::max(0.f,volume);in->balance=clamp1(balance);in->muted=muted;in->monitoring=std::clamp(monitoring,0,3);in->syncOffsetMs=syncOffsetMs;in->owner=this;in->ring=std::make_unique<Ring>(static_cast<size_t>(sampleRate_)*2, sampleRate_);if(running_.load()&&!openInput(*in))return false;inputs_.push_back(std::move(in));return true;}

bool NativeAudioMixer::addExternalInput(const std::string& id,float volume,float balance,bool muted,int monitoring,int syncOffsetMs,bool solo){
    std::lock_guard<std::mutex>l(inputsMutex_);
    if(std::any_of(inputs_.begin(),inputs_.end(),[&](const auto&i){return i->id==id;}))return true;
    auto in=std::make_unique<Input>();in->id=id;in->deviceId=-1;in->external=true;in->volume=std::max(0.f,volume);in->balance=clamp1(balance);in->muted=muted;in->monitoring=std::clamp(monitoring,0,3);in->syncOffsetMs=syncOffsetMs;in->solo=solo;in->owner=this;in->ring=std::make_unique<Ring>(static_cast<size_t>(sampleRate_)*2,sampleRate_);in->sampleRate=sampleRate_;in->active=true;inputs_.push_back(std::move(in));return true;
}

bool NativeAudioMixer::pushExternalPcm(const std::string& id,const float*samples,size_t frames,int channels,int sampleRate,uint64_t ptsUs){
    if(!samples||frames==0||channels<1||sampleRate<8000)return false;
    std::lock_guard<std::mutex>l(inputsMutex_);
    auto it=std::find_if(inputs_.begin(),inputs_.end(),[&](const auto&i){return i->id==id;});
    if(it==inputs_.end()||!(*it)->external||!(*it)->ring)return false;
    auto&in=*(*it);in.sampleRate=sampleRate;in.channels=std::clamp(channels,1,2);
    float peak=0.f;for(size_t n=0;n<frames*static_cast<size_t>(channels);++n)peak=std::max(peak,std::abs(samples[n]));
    in.peak.store(std::max(peak,in.peak.load(std::memory_order_relaxed)*0.85f),std::memory_order_relaxed);
    if(in.resampleScratch.size()<8192*2)in.resampleScratch.resize(8192*2);
    const size_t cap=in.resampleScratch.size()/2;
    const size_t produced=resampleToProgramBus(in,samples,static_cast<int>(std::min<size_t>(frames,static_cast<size_t>(INT32_MAX))),in.resampleScratch.data(),cap);
    if(produced==0)return false;
    if(!in.ring->push(in.resampleScratch.data(),produced,2,ptsUs)){in.ring->clear();return in.ring->push(in.resampleScratch.data(),produced,2,ptsUs);}return true;
}

bool NativeAudioMixer::removeInput(const std::string&id){std::lock_guard<std::mutex>l(inputsMutex_);auto it=std::find_if(inputs_.begin(),inputs_.end(),[&](const auto&i){return i->id==id;});if(it==inputs_.end())return false;closeInput(*(*it));inputs_.erase(it);return true;}
bool NativeAudioMixer::setInputConfig(const std::string&id,float volume,float balance,bool muted,int monitoring,int syncOffsetMs,bool solo){std::lock_guard<std::mutex>l(inputsMutex_);auto it=std::find_if(inputs_.begin(),inputs_.end(),[&](const auto&i){return i->id==id;});if(it==inputs_.end())return false;auto&i=*(*it);i.volume=std::max(0.f,volume);i.balance=clamp1(balance);i.muted=muted;i.monitoring=std::clamp(monitoring,0,3);i.syncOffsetMs=syncOffsetMs;i.solo=solo;return true;}
bool NativeAudioMixer::setInputGate(const std::string&id,bool enabled,float openDb,float closeDb,float attackMs,float holdMs,float releaseMs){
    std::lock_guard<std::mutex>l(inputsMutex_);
    auto it=std::find_if(inputs_.begin(),inputs_.end(),[&](const auto&i){return i->id==id;});
    if(it==inputs_.end())return false;
    auto&g=(*it)->gate;
    const float sr=static_cast<float>(sampleRate_);
    const float open=std::clamp(openDb,-96.f,0.f);
    const float close=std::min(std::clamp(closeDb,-96.f,0.f),open);
    if(enabled&&!g.enabled){g.level=0.f;g.attenuation=0.f;g.heldSamples=0.f;g.open=false;}
    g.enabled=enabled;
    g.openThreshold=std::pow(10.f,open/20.f);
    g.closeThreshold=std::pow(10.f,close/20.f);
    g.attackRate=1.f/std::max(1.f,std::max(0.f,attackMs)*0.001f*sr);
    g.releaseRate=1.f/std::max(1.f,std::max(0.f,releaseMs)*0.001f*sr);
    g.holdSamples=std::max(0.f,holdMs)*0.001f*sr;
    return true;
}
// Same structure as OBS's noise gate: open above the open threshold, close once the decaying
// peak level drops below the close threshold, then hold before releasing.
void NativeAudioMixer::applyGate(Input::Gate&g,float*stereo,size_t frames,float levelDecay){
    for(size_t f=0;f<frames;++f){
        const float cur=std::max(std::abs(stereo[f*2]),std::abs(stereo[f*2+1]));
        if(cur>g.openThreshold&&!g.open)g.open=true;
        if(g.level<g.closeThreshold&&g.open){g.heldSamples=0.f;g.open=false;}
        g.level=std::max(g.level*levelDecay,cur);
        if(g.open)g.attenuation=std::min(1.f,g.attenuation+g.attackRate);
        else{g.heldSamples+=1.f;if(g.heldSamples>g.holdSamples)g.attenuation=std::max(0.f,g.attenuation-g.releaseRate);}
        stereo[f*2]*=g.attenuation;stereo[f*2+1]*=g.attenuation;
    }
}
void NativeAudioMixer::setMonitorVolume(float v){monitorVolume_=std::max(0.f,v);}void NativeAudioMixer::setMonitorMuted(bool m){monitorMuted_=m;}
float NativeAudioMixer::getPeak(const std::string& id) const{std::lock_guard<std::mutex>l(inputsMutex_);auto it=std::find_if(inputs_.begin(),inputs_.end(),[&](const auto&i){return i->id==id;});return it==inputs_.end()?0.f:(*it)->peak.load();}

bool NativeAudioMixer::openInput(Input&input){
    if(input.stream)return true;
    AAudioStreamBuilder*b=nullptr;
    if(AAudio_createStreamBuilder(&b)!=AAUDIO_OK)return false;
    AAudioStreamBuilder_setDirection(b,AAUDIO_DIRECTION_INPUT);
    AAudioStreamBuilder_setPerformanceMode(b,AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(b,AAUDIO_SHARING_MODE_SHARED);
    // Let the USB/Audio HAL choose its native clock/rate first. The mixer resamples into the
    // fixed program bus below, avoiding a forced device-side sample-rate conversion whenever
    // the Android audio path can expose the native rate directly.
    AAudioStreamBuilder_setSampleRate(b,0);
    AAudioStreamBuilder_setChannelCount(b,0);
    AAudioStreamBuilder_setFormat(b,AAUDIO_FORMAT_PCM_FLOAT);
    if(input.deviceId>=0)AAudioStreamBuilder_setDeviceId(b,input.deviceId);
#if __ANDROID_API__ >= 29
    AAudioStreamBuilder_setInputPreset(b,AAUDIO_INPUT_PRESET_UNPROCESSED);
#endif
    AAudioStreamBuilder_setDataCallback(b,&NativeAudioMixer::dataCallback,&input);
    AAudioStreamBuilder_setErrorCallback(b,&NativeAudioMixer::errorCallback,&input);
    AAudioStream*s=nullptr;
    aaudio_result_t r=AAudioStreamBuilder_openStream(b,&s);
    AAudioStreamBuilder_delete(b);
    if(r!=AAUDIO_OK||!s){
        // Some vendor USB paths require an explicit channel count. Retry stereo, then mono,
        // still leaving the sample rate unspecified.
        for(int ch: {2,1}){
            if(s){AAudioStream_close(s);s=nullptr;}
            AAudioStreamBuilder*b2=nullptr;
            if(AAudio_createStreamBuilder(&b2)!=AAUDIO_OK)break;
            AAudioStreamBuilder_setDirection(b2,AAUDIO_DIRECTION_INPUT);
            AAudioStreamBuilder_setPerformanceMode(b2,AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
            AAudioStreamBuilder_setSharingMode(b2,AAUDIO_SHARING_MODE_SHARED);
            AAudioStreamBuilder_setSampleRate(b2,0);
            AAudioStreamBuilder_setChannelCount(b2,ch);
            AAudioStreamBuilder_setFormat(b2,AAUDIO_FORMAT_PCM_FLOAT);
            if(input.deviceId>=0)AAudioStreamBuilder_setDeviceId(b2,input.deviceId);
#if __ANDROID_API__ >= 29
            AAudioStreamBuilder_setInputPreset(b2,AAUDIO_INPUT_PRESET_UNPROCESSED);
#endif
            AAudioStreamBuilder_setDataCallback(b2,&NativeAudioMixer::dataCallback,&input);
            AAudioStreamBuilder_setErrorCallback(b2,&NativeAudioMixer::errorCallback,&input);
            r=AAudioStreamBuilder_openStream(b2,&s);
            AAudioStreamBuilder_delete(b2);
            if(r==AAUDIO_OK&&s)break;
        }
    }
    if(r!=AAUDIO_OK||!s)return false;
    input.stream=s;
    input.channels=std::clamp(AAudioStream_getChannelCount(s),1,2);
    input.sampleRate=std::max(8000,AAudioStream_getSampleRate(s));
    // The callback normally stays well below this; reserve enough for large USB bursts so no
    // allocator work is required during steady-state resampling.
    input.resampleScratch.resize(static_cast<size_t>(8192)*2);
    input.resamplePhase=0.0;
    input.haveResampleHistory=false;
    input.lastSampleL=input.lastSampleR=0.0f;
    r=AAudioStream_requestStart(s);
    if(r!=AAUDIO_OK){closeInput(input);return false;}
    return true;
}void NativeAudioMixer::closeInput(Input&input){input.active=false;if(input.stream){AAudioStream_requestStop(input.stream);AAudioStream_close(input.stream);input.stream=nullptr;}if(input.ring)input.ring->clear();}

bool NativeAudioMixer::openMonitor(){if(monitorStream_)return true;AAudioStreamBuilder*b=nullptr;if(AAudio_createStreamBuilder(&b)!=AAUDIO_OK)return false;AAudioStreamBuilder_setDirection(b,AAUDIO_DIRECTION_OUTPUT);AAudioStreamBuilder_setPerformanceMode(b,AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);AAudioStreamBuilder_setSharingMode(b,AAUDIO_SHARING_MODE_SHARED);AAudioStreamBuilder_setSampleRate(b,sampleRate_);AAudioStreamBuilder_setChannelCount(b,2);AAudioStreamBuilder_setFormat(b,AAUDIO_FORMAT_PCM_FLOAT);if(monitorDeviceId_>=0)AAudioStreamBuilder_setDeviceId(b,monitorDeviceId_);AAudioStreamBuilder_setDataCallback(b,&NativeAudioMixer::monitorCallback,this);aaudio_result_t r=AAudioStreamBuilder_openStream(b,&monitorStream_);AAudioStreamBuilder_delete(b);if(r!=AAUDIO_OK||!monitorStream_){monitorStream_=nullptr;return false;}if(AAudioStream_requestStart(monitorStream_)!=AAUDIO_OK){closeMonitor();return false;}return true;}
void NativeAudioMixer::closeMonitor(){if(!monitorStream_)return;AAudioStream_requestStop(monitorStream_);AAudioStream_close(monitorStream_);monitorStream_=nullptr;}
void NativeAudioMixer::stop(){running_=false;{std::lock_guard<std::mutex>l(inputsMutex_);for(auto&i:inputs_)closeInput(*i);}closeMonitor();if(mixThread_.joinable())mixThread_.join();}
uint64_t NativeAudioMixer::timestampFor(AAudioStream*s,int frames,int sourceRate)const{int64_t pos=0,timeNs=0;if(s&&AAudioStream_getTimestamp(s,CLOCK_MONOTONIC,&pos,&timeNs)==AAUDIO_OK){const int rate=std::max(8000,sourceRate);return static_cast<uint64_t>(std::max<int64_t>(0,timeNs/1000-static_cast<int64_t>(frames)*1000000LL/rate));}return monoUs();}
size_t NativeAudioMixer::resampleToProgramBus(Input& input,const float*src,int frames,float*dst,size_t dstCapacity){
    if(!src||!dst||frames<=0||dstCapacity==0)return 0;
    if(input.sampleRate==sampleRate_){
        const size_t out=std::min<size_t>(static_cast<size_t>(frames),dstCapacity);
        for(size_t i=0;i<out;++i){
            const float l=src[i*input.channels];
            const float r=input.channels>1?src[i*input.channels+1]:l;
            dst[i*2]=l;dst[i*2+1]=r;
        }
        return out;
    }
    const double ratio=static_cast<double>(input.sampleRate)/static_cast<double>(sampleRate_);
    double pos=input.haveResampleHistory ? input.resamplePhase-1.0 : 0.0;
    size_t out=0;
    while(pos+1.0<static_cast<double>(frames) && out<dstCapacity){
        const int i0=static_cast<int>(std::floor(pos));
        const int i1=i0+1;
        const float frac=static_cast<float>(pos-static_cast<double>(i0));
        const float l0=(i0<0)?input.lastSampleL:src[i0*input.channels];
        const float l1=src[i1*input.channels];
        const float r0=(i0<0)?input.lastSampleR:(input.channels>1?src[i0*input.channels+1]:src[i0*input.channels]);
        const float r1=input.channels>1?src[i1*input.channels+1]:src[i1*input.channels];
        dst[out*2]=l0+(l1-l0)*frac; dst[out*2+1]=r0+(r1-r0)*frac; ++out; pos+=ratio;
    }
    input.lastSampleL=src[(frames-1)*input.channels];
    input.lastSampleR=input.channels>1?src[(frames-1)*input.channels+1]:input.lastSampleL;
    input.resamplePhase=pos-static_cast<double>(frames-1);
    input.haveResampleHistory=true;
    return out;
}

void NativeAudioMixer::capture(Input&input,void*audioData,int32_t frames){
    if(!input.active.load()||!input.ring||frames<=0||!audioData)return;
    const uint64_t pts=timestampFor(input.stream,frames,input.sampleRate);
    const float*f=static_cast<const float*>(audioData);
    float pk=0.f;for(int32_t n=0;n<frames*input.channels;++n)pk=std::max(pk,std::abs(f[n]));
    input.peak.store(std::max(pk,input.peak.load()*0.85f),std::memory_order_relaxed);
    const size_t maxFrames=input.resampleScratch.size()/2;
    const size_t outFrames=resampleToProgramBus(input,f,frames,input.resampleScratch.data(),maxFrames);
    if(outFrames==0)return;
    // Timestamp the first output sample in the program clock domain. Linear resampling only
    // changes sample spacing; it does not change the source clock origin.
    const uint64_t outPts=pts;
    if(!input.ring->push(input.resampleScratch.data(),outFrames,2,outPts)){
        // Keep latency bounded: discard the stale ring contents and publish the newest callback.
        input.ring->clear();
        input.ring->push(input.resampleScratch.data(),outFrames,2,outPts);
    }
}aaudio_data_callback_result_t NativeAudioMixer::dataCallback(AAudioStream*,void*userData,void*audioData,int32_t numFrames){auto*in=static_cast<Input*>(userData);if(!in||!in->owner)return AAUDIO_CALLBACK_RESULT_STOP;in->owner->capture(*in,audioData,numFrames);return AAUDIO_CALLBACK_RESULT_CONTINUE;}
void NativeAudioMixer::errorCallback(AAudioStream*,void*userData,aaudio_result_t){if(auto*in=static_cast<Input*>(userData))in->active=false;}
aaudio_data_callback_result_t NativeAudioMixer::monitorCallback(AAudioStream*,void*userData,void*audioData,int32_t numFrames){auto*self=static_cast<NativeAudioMixer*>(userData);if(!self||!audioData||numFrames<=0)return AAUDIO_CALLBACK_RESULT_STOP;float*out=static_cast<float*>(audioData);const size_t frames=static_cast<size_t>(numFrames);if(self->monitorMuted_.load()||!self->monitorRing_){std::fill_n(out,frames*2,0.f);return AAUDIO_CALLBACK_RESULT_CONTINUE;}if(!self->monitorRing_->pop(out,frames)){std::fill_n(out,frames*2,0.f);}const float gain=self->monitorVolume_.load();for(size_t n=0;n<frames*2;++n)out[n]=clamp1(out[n]*gain);return AAUDIO_CALLBACK_RESULT_CONTINUE;}

void NativeAudioMixer::mixLoop(){
    std::vector<float>program(static_cast<size_t>(blockFrames_)*2),monitor(static_cast<size_t>(blockFrames_)*2),tmp(static_cast<size_t>(blockFrames_)*2);
    // Peak-envelope decay for the gate's close detection (~50 ms time constant).
    const float gateLevelDecay=std::exp(-1.f/(0.05f*static_cast<float>(sampleRate_)));
    while(running_){
        std::fill(program.begin(),program.end(),0.f); std::fill(monitor.begin(),monitor.end(),0.f);
        uint64_t pts=UINT64_MAX; bool anyProgram=false,anyMonitor=false,anySolo=false;
        {
            std::lock_guard<std::mutex> l(inputsMutex_);
            for(auto &i:inputs_) anySolo=anySolo || (i->solo.load() && i->monitoring.load()!=0 && !i->muted.load());
            uint64_t commonPts=0; bool haveClock=false;
            for(auto &i:inputs_){
                const int route=i->monitoring.load();
                if(route==0 || i->muted.load() || (anySolo && !i->solo.load()) || !i->ring || i->ring->availableFrames()<static_cast<size_t>(blockFrames_)) continue;
                const int64_t candidate=static_cast<int64_t>(i->ring->startPtsUs()) + static_cast<int64_t>(i->syncOffsetMs.load())*1000LL;
                if(!haveClock || candidate>static_cast<int64_t>(commonPts)){commonPts=static_cast<uint64_t>(std::max<int64_t>(0,candidate));haveClock=true;}
            }
            if(haveClock){
                for(auto &i:inputs_){
                    const int route=i->monitoring.load();
                    if(route==0 || i->muted.load() || (anySolo && !i->solo.load()) || !i->ring || i->ring->availableFrames()<static_cast<size_t>(blockFrames_)) continue;
                    const int64_t desired=static_cast<int64_t>(commonPts) - static_cast<int64_t>(i->syncOffsetMs.load())*1000LL;
                    const int64_t current=static_cast<int64_t>(i->ring->startPtsUs());
                    if(current < desired){
                        const size_t deltaFrames=static_cast<size_t>(std::min<int64_t>(static_cast<int64_t>(i->ring->availableFrames())-static_cast<int64_t>(blockFrames_), std::max<int64_t>(0, (desired-current)*static_cast<int64_t>(sampleRate_)/1000000LL)));
                        if(deltaFrames) i->ring->discard(deltaFrames);
                    }
                    if(i->ring->availableFrames()<static_cast<size_t>(blockFrames_)) continue;
                    const uint64_t sourcePts=i->ring->startPtsUs();
                    if(sourcePts>commonPts+static_cast<uint64_t>(blockFrames_)*1000000ull/static_cast<uint64_t>(sampleRate_)) continue;
                    if(!i->ring->pop(tmp.data(),static_cast<size_t>(blockFrames_))) continue;
                    if(i->gate.enabled)applyGate(i->gate,tmp.data(),static_cast<size_t>(blockFrames_),gateLevelDecay);
                    i->peak.store(i->peak.load(std::memory_order_relaxed)*0.96f,std::memory_order_relaxed);
                    pts=std::min(pts,commonPts);
                    const float v=i->volume.load(),pan=clamp1(i->balance.load()),L=v*(pan>0?1.f-pan:1.f),R=v*(pan<0?1.f+pan:1.f);
                    for(size_t n=0;n<program.size();n+=2){float l=tmp[n]*L,r=tmp[n+1]*R;if(route==1||route==3){program[n]+=l;program[n+1]+=r;anyProgram=true;}if(route==2||route==3){monitor[n]+=l;monitor[n+1]+=r;anyMonitor=true;}}
                }
            }
        }
        if(anyMonitor && monitorRing_){for(float&v:monitor)v=clamp1(v);if(!monitorRing_->push(monitor.data(),static_cast<size_t>(blockFrames_),2,pts==UINT64_MAX?monoUs():pts)){monitorRing_->clear();monitorRing_->push(monitor.data(),static_cast<size_t>(blockFrames_),2,pts==UINT64_MAX?monoUs():pts);}}
        if(!anyProgram){std::this_thread::sleep_for(std::chrono::microseconds(800));continue;}
        for(float&v:program)v=clamp1(v);if(pts==UINT64_MAX)pts=monoUs();
        bool attached=false;JNIEnv*env=nullptr;if(vm_){if(vm_->GetEnv(reinterpret_cast<void**>(&env),JNI_VERSION_1_6)!=JNI_OK){if(vm_->AttachCurrentThread(&env,nullptr)==JNI_OK)attached=true;}}
        if(env&&callback_&&callbackMethod_){jobject buf=env->NewDirectByteBuffer(program.data(),static_cast<jlong>(program.size()*sizeof(float)));if(buf){env->CallVoidMethod(callback_,callbackMethod_,buf,static_cast<jlong>(pts),blockFrames_,2,sampleRate_);env->DeleteLocalRef(buf);if(env->ExceptionCheck())env->ExceptionClear();}}
        if(attached)vm_->DetachCurrentThread();
    }
}

} // namespace stream4k60
