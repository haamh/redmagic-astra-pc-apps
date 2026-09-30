#pragma once
#include "egl_context.h"
#include <GLES3/gl32.h>
#include <android/native_window.h>
#include <jni.h>
#include <map>
#include <mutex>
#include <string>
#include <thread>
#include <atomic>
#include <algorithm>
#include <vector>

namespace stream4k60 {

enum class RawPixelFormat : int { NONE = 0, YUYV = 1, UYVY = 2, NV12 = 3 };

// Ordered per-layer filter chain; must match VideoFilterChain.MAX_STAGES / FLOATS_PER_STAGE in Kotlin.
constexpr int kMaxFilterStages = 8;
constexpr int kFilterStageFloats = 16;
// Stage type ids shared with VideoFilterType.nativeId in Kotlin.
constexpr int kStageLut = 5;
// LUT stages per source; each slot has its own sampler in the layer shader.
constexpr int kMaxLutSlots = 2;

struct SourceLayer {
    std::string id;
    GLuint textureId = 0;
    GLuint auxTextureId = 0;
    float x=0,y=0,w=1920,h=1080,pivotX=0,pivotY=0,rotation=0,scaleX=1,scaleY=1,opacity=1;
    float cropL=0,cropT=0,cropR=0,cropB=0;
    int z=0;
    bool visible=true,flipH=false,flipV=false,external=true;
    int filterCount=0;
    int filterTypes[kMaxFilterStages]={};
    float filterParams[kMaxFilterStages*kFilterStageFloats]={};
    RawPixelFormat rawFormat = RawPixelFormat::NONE;
    int rawWidth=0,rawHeight=0;
    float texMatrix[16];
    SourceLayer(){for(int i=0;i<16;++i)texMatrix[i]=(i%5==0)?1.f:0.f;}
};

class GlCompositor {
public:
    bool initialize(uint32_t width,uint32_t height,int fps,JNIEnv* env);
    void shutdown();
    bool createSource(const std::string& id,JNIEnv* env,jobject& outSurface);
    void releaseSource(const std::string& id,JNIEnv* env);
    void setSourceBufferSize(const std::string& id,int w,int h,JNIEnv* env);
    void updateLayer(const std::string& id,float x,float y,float w,float h,float pivotX,float pivotY,float rot,float sx,float sy,float opacity,float cl,float ct,float cr,float cb,bool visible,int z,bool fh,bool fv);
    void updateFilterChain(const std::string& id,const int* types,int count,const float* params,int paramCount);
    // 3D LUT for a source's LUT slot. rgb is size^3 RGB8 texels, red fastest. key identifies the loaded file.
    void setLut(const std::string& id,int slot,const std::string& key,int size,const uint8_t* rgb,const float* domainMin,const float* domainMax);
    void clearLut(const std::string& id,int slot);
    std::string lutKey(const std::string& id,int slot);
    bool updateRgba(const std::string& id,const uint8_t* pixels,size_t bytes,int width,int height);
    bool updateRaw(const std::string& id,const uint8_t* pixels,size_t bytes,int width,int height,RawPixelFormat format);
    bool setPreviewSurface(JNIEnv* env,jobject surface);
    bool setEncoderSurface(JNIEnv* env,jobject surface);
    bool start(); void stop();
    void setCanvas(uint32_t w,uint32_t h){canvasW_.store(w);canvasH_.store(h);}
    void setVideoSettings(uint32_t w,uint32_t h,int fps){if(w>0&&h>0){canvasW_.store(w);canvasH_.store(h);}if(fps>0)fps_.store(std::clamp(fps,1,240));}
    void setTransition(int type,int durationMs){transitionType_=type;transitionDurationMs_=std::max(1,durationMs);if(type==0)transitionProgress_=0.0f;}
    void setTransitionProgress(float progress){transitionProgress_=std::clamp(progress,0.0f,1.0f);}
    float renderMs() const{return renderMs_.load();}
    uint64_t frames()const{return frames_.load();}
    uint64_t dropped()const{return dropped_.load();}
private:
    struct Source {
        SourceLayer layer;
        bool external=true;
        int pixelW=0,pixelH=0;
        std::vector<uint8_t> pendingRgba;
        std::vector<uint8_t> pendingRaw;
        jobject surfaceTexture=nullptr;
        jobject surface=nullptr;
        jmethodID updateTex=nullptr;
        jmethodID getMatrix=nullptr;
        jobject matrixArray=nullptr;
    };
    EglContext egl_;
    GLuint vao_=0,vbo_=0,program_=0,overlayProgram_=0;
    int transitionType_=0; int transitionDurationMs_=300; float transitionProgress_=0.0f; float transitionR_=0.0f,transitionG_=0.0f,transitionB_=0.0f;
    std::atomic<uint32_t> canvasW_{1920},canvasH_{1080};
    std::atomic<int> fps_{60};
    EGLSurface preview_=EGL_NO_SURFACE,encoder_=EGL_NO_SURFACE;
    ANativeWindow* previewWin_=nullptr,*encoderWin_=nullptr;
    std::map<std::string,Source> sources_;
    std::map<std::string,SourceLayer> pendingFilters_;
    struct Lut {
        std::string key;
        int size=0;
        float domainMin[3]={0,0,0},domainMax[3]={1,1,1};
        std::vector<uint8_t> pending;
        GLuint texture=0;
    };
    // Keyed by "<sourceId>#<slot>"; survives source surface re-creation.
    std::map<std::string,Lut> luts_;
    std::vector<GLuint> lutTexturesToDelete_;
    mutable std::mutex m_;
    std::thread thread_;
    std::atomic<bool> running_{false};
    std::atomic<float> renderMs_{0};
    std::atomic<uint64_t> frames_{0},dropped_{0};
    JavaVM* vm_=nullptr;
    bool setupGl();
    void loop();
    void renderTo(EGLSurface target,int width,int height,int canvasWidth,int canvasHeight);
    void destroyWindow(EGLSurface& s,ANativeWindow*& w);
    Source* source(const std::string&id);
    void applyPendingFilters(SourceLayer& layer,const std::string&id);
    void bindSurfaceTexture(Source& s,JNIEnv* env);
    void renderTransitionOverlay(int width,int height);
};
}
