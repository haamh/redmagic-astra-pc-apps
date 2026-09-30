#include <jni.h>
#include <memory>
#include <atomic>
#include <mutex>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include "../gpu/gl_compositor.h"
#include "../usb/uvc_iso_stream.h"
#include "../usb/uvc_bulk_stream.h"
#include "../audio/native_audio_mixer.h"
static stream4k60::GlCompositor g;
static std::mutex gm;

static std::mutex um;
static std::unordered_map<jlong, std::shared_ptr<stream4k60::UvcIsoStream>> isoStreams;
static std::atomic<jlong> nextIsoHandle{1};
static std::unordered_map<jlong, std::shared_ptr<stream4k60::UvcBulkStream>> bulkStreams;
static std::atomic<jlong> nextBulkHandle{1};
static std::unordered_map<jlong, std::shared_ptr<stream4k60::NativeAudioMixer>> audioMixers;
static std::atomic<jlong> nextAudioHandle{1};

static std::string jstr(JNIEnv*e,jstring s){const char*c=e->GetStringUTFChars(s,nullptr);std::string r=c?c:"";if(c)e->ReleaseStringUTFChars(s,c);return r;}
extern "C" JNIEXPORT jboolean JNICALL Java_com_stream4k60_app_engine_NativeEngine_initializeRenderer(JNIEnv*e,jclass,jint w,jint h,jint fps){std::lock_guard<std::mutex>l(gm);return g.initialize(w,h,fps,e);}
extern "C" JNIEXPORT void JNICALL Java_com_stream4k60_app_engine_NativeEngine_shutdownRenderer(JNIEnv*e,jclass){std::lock_guard<std::mutex>l(gm);g.shutdown();}
extern "C" JNIEXPORT jboolean JNICALL Java_com_stream4k60_app_engine_NativeEngine_startRenderer(JNIEnv*,jclass){return g.start();}
extern "C" JNIEXPORT void JNICALL Java_com_stream4k60_app_engine_NativeEngine_stopRenderer(JNIEnv*,jclass){g.stop();}
extern "C" JNIEXPORT void JNICALL Java_com_stream4k60_app_engine_NativeEngine_setCanvasSize(JNIEnv*,jclass,jint w,jint h){g.setCanvas(w,h);}
extern "C" JNIEXPORT void JNICALL Java_com_stream4k60_app_engine_NativeEngine_setVideoSettings(JNIEnv*,jclass,jint w,jint h,jint fps){g.setVideoSettings(static_cast<uint32_t>(w),static_cast<uint32_t>(h),fps);}
extern "C" JNIEXPORT jboolean JNICALL Java_com_stream4k60_app_engine_NativeEngine_setPreviewSurface(JNIEnv*e,jclass,jobject s){return g.setPreviewSurface(e,s);}
extern "C" JNIEXPORT jboolean JNICALL Java_com_stream4k60_app_engine_NativeEngine_setEncoderSurface(JNIEnv*e,jclass,jobject s){return g.setEncoderSurface(e,s);}
extern "C" JNIEXPORT jobject JNICALL Java_com_stream4k60_app_engine_NativeEngine_createSourceSurface(JNIEnv*e,jclass,jstring id){jobject out=nullptr;return g.createSource(jstr(e,id),e,out)?out:nullptr;}
extern "C" JNIEXPORT void JNICALL Java_com_stream4k60_app_engine_NativeEngine_setSourceBufferSize(JNIEnv*e,jclass,jstring id,jint w,jint h){g.setSourceBufferSize(jstr(e,id),w,h,e);}
extern "C" JNIEXPORT void JNICALL Java_com_stream4k60_app_engine_NativeEngine_releaseSourceSurface(JNIEnv*e,jclass,jstring id){g.releaseSource(jstr(e,id),e);}
extern "C" JNIEXPORT jboolean JNICALL Java_com_stream4k60_app_engine_NativeEngine_updateSourceRgba(JNIEnv*e,jclass,jstring id,jbyteArray data,jint w,jint h){if(!data)return JNI_FALSE;jsize n=e->GetArrayLength(data);std::vector<uint8_t>buf((size_t)n);e->GetByteArrayRegion(data,0,n,reinterpret_cast<jbyte*>(buf.data()));return g.updateRgba(jstr(e,id),buf.data(),buf.size(),w,h);}
extern "C" JNIEXPORT jboolean JNICALL Java_com_stream4k60_app_engine_NativeEngine_updateSourceYuvDirect(JNIEnv*e,jclass,jstring id,jobject frame,jint w,jint h,jint fmt){if(!frame)return JNI_FALSE;void*p=e->GetDirectBufferAddress(frame);jlong n=e->GetDirectBufferCapacity(frame);if(!p||n<=0)return JNI_FALSE;auto f=static_cast<stream4k60::RawPixelFormat>(fmt);return g.updateRaw(jstr(e,id),reinterpret_cast<const uint8_t*>(p),(size_t)n,w,h,f);}
extern "C" JNIEXPORT void JNICALL Java_com_stream4k60_app_engine_NativeEngine_setSourceTextureParameters(JNIEnv*e,jclass,jstring id,jfloat x,jfloat y,jfloat w,jfloat h,jfloat px,jfloat py,jfloat r,jfloat sx,jfloat sy,jfloat op,jfloat cl,jfloat ct,jfloat cr,jfloat cb,jboolean v,jint z,jboolean fh,jboolean fv){g.updateLayer(jstr(e,id),x,y,w,h,px,py,r,sx,sy,op,cl,ct,cr,cb,v,z,fh,fv);}
extern "C" JNIEXPORT void JNICALL Java_com_stream4k60_app_engine_NativeEngine_setSourceEffects(JNIEnv*e,jclass,jstring id,jfloat br,jfloat co,jfloat sa,jfloat ga,jfloat hue,jboolean key,jfloat kr,jfloat kg,jfloat kb,jfloat sim,jfloat smooth){g.updateEffects(jstr(e,id),br,co,sa,ga,hue,key,kr,kg,kb,sim,smooth);}
extern "C" JNIEXPORT void JNICALL Java_com_stream4k60_app_engine_NativeEngine_removeSourceLayer(JNIEnv*e,jclass,jstring id){g.releaseSource(jstr(e,id),e);}
extern "C" JNIEXPORT void JNICALL Java_com_stream4k60_app_engine_NativeEngine_setTransition(JNIEnv*,jclass,jint type,jint duration){g.setTransition(type,duration);}
extern "C" JNIEXPORT void JNICALL Java_com_stream4k60_app_engine_NativeEngine_setTransitionProgress(JNIEnv*,jclass,jfloat p){g.setTransitionProgress(p);}
extern "C" JNIEXPORT jfloat JNICALL Java_com_stream4k60_app_engine_NativeEngine_getRenderTimeMs(JNIEnv*,jclass){return g.renderMs();}
extern "C" JNIEXPORT jlong JNICALL Java_com_stream4k60_app_engine_NativeEngine_getDroppedFrames(JNIEnv*,jclass){return (jlong)g.dropped();}
extern "C" JNIEXPORT jlong JNICALL Java_com_stream4k60_app_engine_NativeEngine_getTotalFrames(JNIEnv*,jclass){return (jlong)g.frames();}
extern "C" JNIEXPORT jlong JNICALL Java_com_stream4k60_app_engine_NativeUsbIso_start(JNIEnv* env,jclass,jint fd,jint endpoint,jint packetBytes,jint packetsPerUrb,jint urbCount,jobject callback){
    if(fd<0||endpoint<0||packetBytes<=0||!callback)return 0;
    jclass cls=env->GetObjectClass(callback);
    if(!cls)return 0;
    jmethodID method=env->GetMethodID(cls,"onNativeFrame","(Ljava/nio/ByteBuffer;JI)V");
    if(!method)return 0;
    jobject global=env->NewGlobalRef(callback);
    if(!global)return 0;
    JavaVM* vm=nullptr;env->GetJavaVM(&vm);
    auto stream=std::make_shared<stream4k60::UvcIsoStream>(fd,(uint8_t)endpoint,(size_t)packetBytes,packetsPerUrb,urbCount,global,method,vm);
    if(!stream->start()){env->DeleteGlobalRef(global);return 0;}
    const jlong handle=nextIsoHandle.fetch_add(1);
    {std::lock_guard<std::mutex> lock(um);isoStreams.emplace(handle,std::move(stream));}
    return handle;
}
extern "C" JNIEXPORT void JNICALL Java_com_stream4k60_app_engine_NativeUsbIso_stop(JNIEnv* env,jclass,jlong handle){
    std::shared_ptr<stream4k60::UvcIsoStream> stream;
    {std::lock_guard<std::mutex> lock(um);auto it=isoStreams.find(handle);if(it==isoStreams.end())return;stream=it->second;isoStreams.erase(it);}
    stream->stop();
}
extern "C" JNIEXPORT jlong JNICALL Java_com_stream4k60_app_engine_NativeUsbIso_frames(JNIEnv*,jclass,jlong handle){std::lock_guard<std::mutex> lock(um);auto it=isoStreams.find(handle);return it==isoStreams.end()?0:(jlong)it->second->frames();}
extern "C" JNIEXPORT jlong JNICALL Java_com_stream4k60_app_engine_NativeUsbIso_packetErrors(JNIEnv*,jclass,jlong handle){std::lock_guard<std::mutex> lock(um);auto it=isoStreams.find(handle);return it==isoStreams.end()?0:(jlong)it->second->packetErrors();}
extern "C" JNIEXPORT jlong JNICALL Java_com_stream4k60_app_engine_NativeUsbIso_droppedFrames(JNIEnv*,jclass,jlong handle){std::lock_guard<std::mutex> lock(um);auto it=isoStreams.find(handle);return it==isoStreams.end()?0:(jlong)it->second->droppedFrames();}


extern "C" JNIEXPORT jlong JNICALL Java_com_stream4k60_app_engine_NativeUsbBulk_start(JNIEnv* env,jclass,jint fd,jint endpoint,jint packetBytes,jint transferBytes,jint urbCount,jobject callback){
    if(fd<0||endpoint<0||packetBytes<=0||transferBytes<=0||!callback)return 0;
    jclass cls=env->GetObjectClass(callback); if(!cls)return 0;
    jmethodID method=env->GetMethodID(cls,"onNativeFrame","(Ljava/nio/ByteBuffer;JI)V"); if(!method)return 0;
    jobject global=env->NewGlobalRef(callback); if(!global)return 0;
    JavaVM* vm=nullptr; env->GetJavaVM(&vm);
    auto stream=std::make_shared<stream4k60::UvcBulkStream>(fd,(uint8_t)endpoint,(size_t)packetBytes,(size_t)transferBytes,urbCount,global,method,vm);
    if(!stream->start()){env->DeleteGlobalRef(global);return 0;}
    jlong handle=nextBulkHandle.fetch_add(1); {std::lock_guard<std::mutex> lock(um); bulkStreams.emplace(handle,std::move(stream));} return handle;
}
extern "C" JNIEXPORT void JNICALL Java_com_stream4k60_app_engine_NativeUsbBulk_stop(JNIEnv*,jclass,jlong handle){std::shared_ptr<stream4k60::UvcBulkStream> s;{std::lock_guard<std::mutex> lock(um);auto it=bulkStreams.find(handle);if(it==bulkStreams.end())return;s=it->second;bulkStreams.erase(it);}s->stop();}
extern "C" JNIEXPORT jlong JNICALL Java_com_stream4k60_app_engine_NativeUsbBulk_frames(JNIEnv*,jclass,jlong handle){std::lock_guard<std::mutex> lock(um);auto it=bulkStreams.find(handle);return it==bulkStreams.end()?0:(jlong)it->second->frames();}
extern "C" JNIEXPORT jlong JNICALL Java_com_stream4k60_app_engine_NativeUsbBulk_errors(JNIEnv*,jclass,jlong handle){std::lock_guard<std::mutex> lock(um);auto it=bulkStreams.find(handle);return it==bulkStreams.end()?0:(jlong)it->second->transferErrors();}
extern "C" JNIEXPORT jlong JNICALL Java_com_stream4k60_app_engine_NativeUsbBulk_droppedFrames(JNIEnv*,jclass,jlong handle){std::lock_guard<std::mutex> lock(um);auto it=bulkStreams.find(handle);return it==bulkStreams.end()?0:(jlong)it->second->droppedFrames();}


extern "C" JNIEXPORT jlong JNICALL Java_com_stream4k60_app_engine_NativeAudioMixer_start(JNIEnv* env,jclass,jobject callback,jint sampleRate,jint channels,jint blockFrames,jint monitorDeviceId,jboolean monitorEnabled){
    if(!callback||sampleRate<=0||blockFrames<=0)return 0;
    JavaVM* vm=nullptr;if(env->GetJavaVM(&vm)!=JNI_OK)return 0;
    auto mixer=std::make_shared<stream4k60::NativeAudioMixer>(vm,callback,sampleRate,channels,blockFrames,monitorDeviceId,monitorEnabled);
    if(!mixer->start())return 0;
    jlong h=nextAudioHandle.fetch_add(1);{std::lock_guard<std::mutex> l(um);audioMixers.emplace(h,mixer);}return h;
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_stream4k60_app_engine_NativeAudioMixer_addInput(JNIEnv* env,jclass,jlong handle,jstring sourceId,jint deviceId,jfloat volume,jfloat balance,jboolean muted,jint monitoring,jint syncOffsetMs){
    std::shared_ptr<stream4k60::NativeAudioMixer> m;{std::lock_guard<std::mutex> l(um);auto it=audioMixers.find(handle);if(it==audioMixers.end())return JNI_FALSE;m=it->second;}return m->addInput(jstr(env,sourceId),deviceId,volume,balance,muted,monitoring,syncOffsetMs)?JNI_TRUE:JNI_FALSE;
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_stream4k60_app_engine_NativeAudioMixer_addExternalInput(JNIEnv* env,jclass,jlong handle,jstring sourceId,jfloat volume,jfloat balance,jboolean muted,jint monitoring,jint syncOffsetMs,jboolean solo){std::shared_ptr<stream4k60::NativeAudioMixer> m;{std::lock_guard<std::mutex> l(um);auto it=audioMixers.find(handle);if(it==audioMixers.end())return JNI_FALSE;m=it->second;}return m->addExternalInput(jstr(env,sourceId),volume,balance,muted,monitoring,syncOffsetMs,solo)?JNI_TRUE:JNI_FALSE;}
extern "C" JNIEXPORT jboolean JNICALL Java_com_stream4k60_app_engine_NativeAudioMixer_pushExternalPcm(JNIEnv* env,jclass,jlong handle,jstring sourceId,jobject pcm,jint frames,jint channels,jint sampleRate,jlong ptsUs){
    if(!pcm||frames<=0)return JNI_FALSE;void*ptr=env->GetDirectBufferAddress(pcm);jlong bytes=env->GetDirectBufferCapacity(pcm);if(!ptr||bytes<=0)return JNI_FALSE;
    std::shared_ptr<stream4k60::NativeAudioMixer> m;{std::lock_guard<std::mutex> l(um);auto it=audioMixers.find(handle);if(it==audioMixers.end())return JNI_FALSE;m=it->second;}
    const size_t required=static_cast<size_t>(frames)*static_cast<size_t>(std::max(1,channels))*sizeof(float);if(static_cast<size_t>(bytes)<required)return JNI_FALSE;
    return m->pushExternalPcm(jstr(env,sourceId),static_cast<const float*>(ptr),frames,channels,sampleRate,static_cast<uint64_t>(ptsUs))?JNI_TRUE:JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_stream4k60_app_engine_NativeAudioMixer_removeInput(JNIEnv* env,jclass,jlong handle,jstring sourceId){
    std::shared_ptr<stream4k60::NativeAudioMixer> m;{std::lock_guard<std::mutex> l(um);auto it=audioMixers.find(handle);if(it==audioMixers.end())return JNI_FALSE;m=it->second;}return m->removeInput(jstr(env,sourceId))?JNI_TRUE:JNI_FALSE;
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_stream4k60_app_engine_NativeAudioMixer_setInputConfig(JNIEnv* env,jclass,jlong handle,jstring sourceId,jfloat volume,jfloat balance,jboolean muted,jint monitoring,jint syncOffsetMs,jboolean solo){
    std::shared_ptr<stream4k60::NativeAudioMixer> m;{std::lock_guard<std::mutex> l(um);auto it=audioMixers.find(handle);if(it==audioMixers.end())return JNI_FALSE;m=it->second;}return m->setInputConfig(jstr(env,sourceId),volume,balance,muted,monitoring,syncOffsetMs,solo)?JNI_TRUE:JNI_FALSE;
}
extern "C" JNIEXPORT void JNICALL Java_com_stream4k60_app_engine_NativeAudioMixer_setMonitorVolume(JNIEnv*,jclass,jlong handle,jfloat volume){std::shared_ptr<stream4k60::NativeAudioMixer> m;{std::lock_guard<std::mutex> l(um);auto it=audioMixers.find(handle);if(it==audioMixers.end())return;m=it->second;}m->setMonitorVolume(volume);}
extern "C" JNIEXPORT void JNICALL Java_com_stream4k60_app_engine_NativeAudioMixer_setMonitorMuted(JNIEnv*,jclass,jlong handle,jboolean muted){std::shared_ptr<stream4k60::NativeAudioMixer> m;{std::lock_guard<std::mutex> l(um);auto it=audioMixers.find(handle);if(it==audioMixers.end())return;m=it->second;}m->setMonitorMuted(muted);}
extern "C" JNIEXPORT jfloat JNICALL Java_com_stream4k60_app_engine_NativeAudioMixer_getPeak(JNIEnv* env,jclass,jlong handle,jstring sourceId){std::shared_ptr<stream4k60::NativeAudioMixer> m;{std::lock_guard<std::mutex> l(um);auto it=audioMixers.find(handle);if(it==audioMixers.end())return 0.0f;m=it->second;}return m->getPeak(jstr(env,sourceId));}
extern "C" JNIEXPORT void JNICALL Java_com_stream4k60_app_engine_NativeAudioMixer_stop(JNIEnv*,jclass,jlong handle){std::shared_ptr<stream4k60::NativeAudioMixer> m;{std::lock_guard<std::mutex> l(um);auto it=audioMixers.find(handle);if(it==audioMixers.end())return;m=it->second;audioMixers.erase(it);}m->stop();}

JNIEXPORT jint JNI_OnLoad(JavaVM*,void*){return JNI_VERSION_1_6;}
