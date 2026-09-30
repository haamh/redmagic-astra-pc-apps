#include "gl_compositor.h"
#include <EGL/eglext.h>
#include <GLES2/gl2ext.h>
#if __ANDROID_API__ >= 31
#include <android/performance_hint.h>
#include <sys/syscall.h>
#include <unistd.h>
#endif
#include <android/native_window_jni.h>
#include <chrono>
#include <cmath>
#include <algorithm>
#include <cstring>

namespace stream4k60 {
static const char* VS=R"GLSL(#version 320 es
layout(location=0)in vec2 aPos;layout(location=1)in vec2 aUv;
uniform vec2 uCanvas;uniform vec4 uRect;uniform vec2 uScale;uniform vec2 uPivot;uniform float uRotation;uniform mat4 uTexMatrix;
out vec2 vUv;
void main(){vec2 p=(aPos*.5+.5)*uRect.zw*uScale;p-=uPivot;float cs=cos(uRotation),sn=sin(uRotation);p=vec2(p.x*cs-p.y*sn,p.x*sn+p.y*cs)+uPivot+uRect.xy;vec2 clip=vec2(p.x/uCanvas.x*2.-1.,1.-p.y/uCanvas.y*2.);gl_Position=vec4(clip,0,1);vUv=(uTexMatrix*vec4(aUv,0,1)).xy;})GLSL";
static const char* FS=R"GLSL(#version 320 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
in vec2 vUv;
uniform samplerExternalOES uExtTex;
uniform sampler2D u2DTex;
uniform sampler2D uRawTex;
uniform sampler2D uRawAuxTex;
uniform int uExternal;
uniform int uRawFormat;
uniform vec2 uRawSize;
uniform float uOpacity;
uniform vec4 uCrop;
uniform bool uFlipH;uniform bool uFlipV;
const int MAX_STAGES=8;
uniform int uStageCount;
uniform int uStageType[MAX_STAGES];
uniform vec4 uStageParams[MAX_STAGES*4];
uniform vec2 uTexel;
precision highp sampler3D;
uniform sampler3D uLut0;
uniform sampler3D uLut1;
uniform vec3 uLutMin[2];
uniform vec3 uLutMax[2];
uniform float uLutSize[2];
out vec4 frag;
vec3 yuvToRgb(float y,float u,float v){y=1.16438356*(y-0.0627451);u=u-0.5019608;v=v-0.5019608;return clamp(vec3(y+1.5960268*v,y-0.391762*u-0.812968*v,y+2.017232*u),0.0,1.0);}
// Raw USB frames are sampled at the cropped/flipped coordinate like every other source type.
vec4 rawColorAt(vec2 st){if(uRawFormat==1||uRawFormat==2){float px=clamp(st.x,0.0,1.0)*uRawSize.x;float pair=floor(px*0.5);float which=mod(floor(px),2.0);vec2 tc=(vec2(pair+0.5,floor(clamp(st.y,0.0,1.0)*uRawSize.y)+0.5))/vec2(max(1.0,ceil(uRawSize.x*0.5)),uRawSize.y);vec4 p=texture(uRawTex,tc);float y,u,v;if(uRawFormat==1){y=(which<0.5)?p.r:p.b;u=p.g;v=p.a;}else{y=(which<0.5)?p.g:p.a;u=p.r;v=p.b;}return vec4(yuvToRgb(y,u,v),1.0);}if(uRawFormat==3){float y=texture(uRawTex,st).r;vec2 uv=texture(uRawAuxTex,st).rg;return vec4(yuvToRgb(y,uv.r,uv.g),1.0);}return vec4(0.0);}
vec4 sampleSource(vec2 st){return (uExternal==1)?texture(uExtTex,st):(uRawFormat!=0?rawColorAt(st):texture(u2DTex,st));}
const vec3 LUMA=vec3(0.2126,0.7152,0.0722);
// Rotation about the gray axis (Rodrigues), which preserves neutral colors.
vec3 hueRotate(vec3 c,float degrees){float a=radians(degrees);float cs=cos(a),sn=sin(a);vec3 k=vec3(0.57735026);
return c*cs+cross(k,c)*sn+k*dot(k,c)*(1.0-cs);}
// p0=(brightness,contrast,saturation,gammaExponent) p1=(hueDegrees,opacity) p2=multiply.rgb p3=add.rgb
vec4 colorCorrection(vec4 c,vec4 p0,vec4 p1,vec4 p2,vec4 p3){vec3 rgb=pow(max(c.rgb,vec3(0.0)),vec3(p0.w));
rgb=(rgb-0.5)*p0.y+0.5+p0.x;float l=dot(rgb,LUMA);rgb=mix(vec3(l),rgb,p0.z);
if(p1.x!=0.0)rgb=hueRotate(rgb,p1.x);rgb=rgb*p2.rgb+p3.rgb;return vec4(clamp(rgb,0.0,1.0),c.a*p1.y);}
// OBS key filters' own adjustment: p=(opacity,contrast,brightness,gammaExponent)
vec4 keyAdjust(vec4 c,vec4 p){return vec4(clamp(pow(max(c.rgb,vec3(0.0)),vec3(p.w))*p.y+p.z,0.0,1.0),c.a*p.x);}
// p0=(keyCr,keyCb,similarity,smoothness) p1.x=spill; distance measured in CbCr like OBS chroma_key_filter.
vec4 chromaKey(vec4 c,vec4 p0,vec4 p1,vec4 p2){float cb=dot(c.rgb,vec3(-0.100644,-0.338572,0.439216))+0.501961;
float cr=dot(c.rgb,vec3(0.439216,-0.398942,-0.040274))+0.501961;float base=distance(vec2(cr,cb),p0.xy)-p0.z;
c.a*=pow(clamp(base/p0.w,0.0,1.0),1.5);float spill=pow(clamp(base/p1.x,0.0,1.0),1.5);
c.rgb=mix(vec3(dot(c.rgb,LUMA)),c.rgb,spill);return keyAdjust(c,p2);}
// p0=(key.rgb,similarity) p1.x=smoothness; RGB distance like OBS color_key_filter.
vec4 colorKey(vec4 c,vec4 p0,vec4 p1,vec4 p2){float base=distance(c.rgb,p0.rgb)-p0.w;
c.a*=clamp(max(base,0.0)/p1.x,0.0,1.0);return keyAdjust(c,p2);}
// p0=(lumaMin,lumaMax,lumaMinSmooth,lumaMaxSmooth)
vec4 lumaKey(vec4 c,vec4 p0){float l=dot(c.rgb,LUMA);
float lo=p0.z>0.0?smoothstep(p0.x,p0.x+p0.z,l):step(p0.x,l);
float hi=p0.w>0.0?1.0-smoothstep(p0.y-p0.w,p0.y,l):step(l,p0.y);return vec4(c.rgb,c.a*lo*hi);}
// p0=(amount,slot). 3D LUT indexed r,g,b; input remapped from the LUT's domain.
vec4 applyLut(vec4 c,vec4 p0){bool s0=p0.y<0.5;vec3 lo=s0?uLutMin[0]:uLutMin[1];vec3 hi=s0?uLutMax[0]:uLutMax[1];float n=s0?uLutSize[0]:uLutSize[1];
vec3 x=clamp((c.rgb-lo)/max(hi-lo,vec3(1e-5)),0.0,1.0);vec3 coord=x*((n-1.0)/n)+0.5/n;
vec3 graded=s0?texture(uLut0,coord).rgb:texture(uLut1,coord).rgb;return vec4(mix(c.rgb,graded,p0.x),c.a);}
vec4 applyPixelStage(vec4 c,int i){int b=i*4;int t=uStageType[i];
if(t==1)return colorCorrection(c,uStageParams[b],uStageParams[b+1],uStageParams[b+2],uStageParams[b+3]);
if(t==2)return chromaKey(c,uStageParams[b],uStageParams[b+1],uStageParams[b+2]);
if(t==3)return colorKey(c,uStageParams[b],uStageParams[b+1],uStageParams[b+2]);
if(t==4)return lumaKey(c,uStageParams[b]);
if(t==5)return applyLut(c,uStageParams[b]);
return c;}
// Stages before `end`, skipping sharpen (used to filter a sharpen stage's neighbor samples).
vec4 applyPixelStages(vec4 c,int end){for(int i=0;i<MAX_STAGES;++i){if(i>=end)break;c=applyPixelStage(c,i);}return c;}
vec4 applyFilters(vec4 c,vec2 st){for(int i=0;i<MAX_STAGES;++i){if(i>=uStageCount)break;
if(uStageType[i]==6){float s=uStageParams[i*4].x;
vec4 n=applyPixelStages(sampleSource(st+vec2(0.0,-uTexel.y)),i)+applyPixelStages(sampleSource(st+vec2(0.0,uTexel.y)),i)
+applyPixelStages(sampleSource(st+vec2(-uTexel.x,0.0)),i)+applyPixelStages(sampleSource(st+vec2(uTexel.x,0.0)),i);
c=vec4(clamp(c.rgb+(4.0*c.rgb-n.rgb)*s,0.0,1.0),c.a);}
else c=applyPixelStage(c,i);}return c;}
void main(){vec2 uv=mix(uCrop.xy,uCrop.zw,vUv);if(uFlipH)uv.x=1.-uv.x;if(uFlipV)uv.y=1.-uv.y;vec4 c=sampleSource(uv);c=applyFilters(c,uv);frag=vec4(c.rgb,c.a*uOpacity);})GLSL";
static GLuint compileShader(GLenum t,const char*s){GLuint x=glCreateShader(t);glShaderSource(x,1,&s,nullptr);glCompileShader(x);GLint ok=0;glGetShaderiv(x,GL_COMPILE_STATUS,&ok);if(!ok){glDeleteShader(x);return 0;}return x;}
static GLuint createProgram(){auto v=compileShader(GL_VERTEX_SHADER,VS);auto f=compileShader(GL_FRAGMENT_SHADER,FS);if(!v||!f){if(v)glDeleteShader(v);if(f)glDeleteShader(f);return 0;}GLuint p=glCreateProgram();glAttachShader(p,v);glAttachShader(p,f);glBindAttribLocation(p,0,"aPos");glBindAttribLocation(p,1,"aUv");glLinkProgram(p);GLint ok=0;glGetProgramiv(p,GL_LINK_STATUS,&ok);glDeleteShader(v);glDeleteShader(f);return ok?p:0;}

bool GlCompositor::initialize(uint32_t w,uint32_t h,int fps,JNIEnv* env){canvasW_.store(w);canvasH_.store(h);fps_.store(std::clamp(fps,1,240));env->GetJavaVM(&vm_);if(!egl_.initialize()||!egl_.createOffscreenContext())return false;return setupGl();}
bool GlCompositor::setupGl(){program_=createProgram();if(!program_)return false;const char* ovs=R"GLSL(#version 320 es
layout(location=0)in vec2 aPos;out vec2 v;void main(){v=aPos;gl_Position=vec4(aPos,0.,1.);}
)GLSL";const char* ofs=R"GLSL(#version 320 es
precision highp float;uniform vec4 uColor;out vec4 frag;void main(){frag=uColor;}
)GLSL";auto compileLocal=[](GLenum t,const char*src)->GLuint{GLuint x=glCreateShader(t);glShaderSource(x,1,&src,nullptr);glCompileShader(x);GLint ok=0;glGetShaderiv(x,GL_COMPILE_STATUS,&ok);if(!ok){glDeleteShader(x);return 0;}return x;};GLuint ov=compileLocal(GL_VERTEX_SHADER,ovs),of=compileLocal(GL_FRAGMENT_SHADER,ofs);if(!ov||!of){if(ov)glDeleteShader(ov);if(of)glDeleteShader(of);return false;}overlayProgram_=glCreateProgram();glAttachShader(overlayProgram_,ov);glAttachShader(overlayProgram_,of);glLinkProgram(overlayProgram_);GLint ol=0;glGetProgramiv(overlayProgram_,GL_LINK_STATUS,&ol);glDeleteShader(ov);glDeleteShader(of);if(!ol)return false;const float q[]={-1,-1,0,1,1,-1,1,1,-1,1,0,0,1,1,1,0};glGenVertexArrays(1,&vao_);glGenBuffers(1,&vbo_);glBindVertexArray(vao_);glBindBuffer(GL_ARRAY_BUFFER,vbo_);glBufferData(GL_ARRAY_BUFFER,sizeof(q),q,GL_STATIC_DRAW);glEnableVertexAttribArray(0);glVertexAttribPointer(0,2,GL_FLOAT,GL_FALSE,4*sizeof(float),(void*)0);glEnableVertexAttribArray(1);glVertexAttribPointer(1,2,GL_FLOAT,GL_FALSE,4*sizeof(float),(void*)(2*sizeof(float)));glBindVertexArray(0);return true;}
void GlCompositor::destroyWindow(EGLSurface&s,ANativeWindow*&w){if(s!=EGL_NO_SURFACE){eglDestroySurface(egl_.display(),s);s=EGL_NO_SURFACE;}if(w){ANativeWindow_release(w);w=nullptr;}}
bool GlCompositor::setPreviewSurface(JNIEnv* env,jobject js){std::lock_guard<std::mutex>lk(m_);destroyWindow(preview_,previewWin_);if(!js)return true;previewWin_=ANativeWindow_fromSurface(env,js);if(!previewWin_)return false;preview_=egl_.createWindowSurface(previewWin_);return preview_!=EGL_NO_SURFACE;}
bool GlCompositor::setEncoderSurface(JNIEnv* env,jobject js){std::lock_guard<std::mutex>lk(m_);destroyWindow(encoder_,encoderWin_);if(!js)return true;encoderWin_=ANativeWindow_fromSurface(env,js);if(!encoderWin_)return false;encoder_=egl_.createWindowSurface(encoderWin_);return encoder_!=EGL_NO_SURFACE;}
void GlCompositor::bindSurfaceTexture(Source&s,JNIEnv*env){if(!s.surfaceTexture)return;jclass c=env->GetObjectClass(s.surfaceTexture);s.updateTex=env->GetMethodID(c,"updateTexImage","()V");s.getMatrix=env->GetMethodID(c,"getTransformMatrix","([F)V");jfloatArray a=(jfloatArray)env->NewFloatArray(16);s.matrixArray=env->NewGlobalRef(a);env->DeleteLocalRef(a);}
 bool GlCompositor::createSource(const std::string&id,JNIEnv*env,jobject&out){bool was=running_.load();if(was)stop();eglMakeCurrent(egl_.display(),egl_.surface(),egl_.surface(),egl_.context());std::lock_guard<std::mutex>lk(m_);if(sources_.count(id)){out=env->NewLocalRef(sources_[id].surface);if(was)start();return true;}GLuint tex=0;glGenTextures(1,&tex);glBindTexture(GL_TEXTURE_EXTERNAL_OES,tex);glTexParameteri(GL_TEXTURE_EXTERNAL_OES,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_EXTERNAL_OES,GL_TEXTURE_MAG_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_EXTERNAL_OES,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_EXTERNAL_OES,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);jclass stc=env->FindClass("android/graphics/SurfaceTexture");jmethodID ctor=env->GetMethodID(stc,"<init>","(I)V");jobject st=env->NewObject(stc,ctor,(jint)tex);jclass sc=env->FindClass("android/view/Surface");jmethodID sctor=env->GetMethodID(sc,"<init>","(Landroid/graphics/SurfaceTexture;)V");jobject surf=env->NewObject(sc,sctor,st);Source x;x.layer.id=id;x.layer.external=true;applyPendingFilters(x.layer,id);x.external=true;x.layer.textureId=tex;x.surfaceTexture=env->NewGlobalRef(st);x.surface=env->NewGlobalRef(surf);bindSurfaceTexture(x,env);sources_[id]=x;out=env->NewLocalRef(surf);env->DeleteLocalRef(st);env->DeleteLocalRef(surf);if(was)start();return true;}
void GlCompositor::setSourceBufferSize(const std::string&id,int w,int h,JNIEnv*env){std::lock_guard<std::mutex>lk(m_);auto it=sources_.find(id);if(it==sources_.end()||!it->second.surfaceTexture)return;jclass c=env->GetObjectClass(it->second.surfaceTexture);jmethodID m=env->GetMethodID(c,"setDefaultBufferSize","(II)V");if(m)env->CallVoidMethod(it->second.surfaceTexture,m,w,h);if(w>0&&h>0){it->second.pixelW=w;it->second.pixelH=h;}}
void GlCompositor::releaseSource(const std::string&id,JNIEnv*env){bool was=running_.load();if(was)stop();eglMakeCurrent(egl_.display(),egl_.surface(),egl_.surface(),egl_.context());std::lock_guard<std::mutex>lk(m_);auto it=sources_.find(id);if(it==sources_.end()){if(was)start();return;}auto&s=it->second;if(s.matrixArray)env->DeleteGlobalRef(s.matrixArray);if(s.surfaceTexture)env->DeleteGlobalRef(s.surfaceTexture);if(s.surface)env->DeleteGlobalRef(s.surface);if(s.layer.textureId)glDeleteTextures(1,&s.layer.textureId);if(s.layer.auxTextureId)glDeleteTextures(1,&s.layer.auxTextureId);sources_.erase(it);if(was)start();}
GlCompositor::Source* GlCompositor::source(const std::string&id){auto it=sources_.find(id);return it==sources_.end()?nullptr:&it->second;}
void GlCompositor::applyPendingFilters(SourceLayer&layer,const std::string&id){auto it=pendingFilters_.find(id);if(it==pendingFilters_.end())return;const auto&p=it->second;layer.filterCount=p.filterCount;std::copy(std::begin(p.filterTypes),std::end(p.filterTypes),layer.filterTypes);std::copy(std::begin(p.filterParams),std::end(p.filterParams),layer.filterParams);pendingFilters_.erase(it);}
void GlCompositor::updateLayer(const std::string&id,float x,float y,float w,float h,float pivotX,float pivotY,float r,float sx,float sy,float op,float cl,float ct,float cr,float cb,bool vis,int z,bool fh,bool fv){std::lock_guard<std::mutex>lk(m_);auto*s=source(id);if(!s)return;s->layer.x=x;s->layer.y=y;s->layer.w=w;s->layer.h=h;s->layer.pivotX=pivotX;s->layer.pivotY=pivotY;s->layer.rotation=r;s->layer.scaleX=sx;s->layer.scaleY=sy;s->layer.opacity=op;s->layer.cropL=cl;s->layer.cropT=ct;s->layer.cropR=cr;s->layer.cropB=cb;s->layer.visible=vis;s->layer.z=z;s->layer.flipH=fh;s->layer.flipV=fv;}
bool GlCompositor::updateRgba(const std::string&id,const uint8_t*pixels,size_t bytes,int width,int height){if(!pixels||bytes==0||width<=0||height<=0)return false;std::lock_guard<std::mutex>lk(m_);auto &s=sources_[id];s.layer.id=id;applyPendingFilters(s.layer,id);s.external=false;s.layer.external=false;s.layer.rawFormat=RawPixelFormat::NONE;s.pixelW=width;s.pixelH=height;s.pendingRgba.assign(pixels,pixels+bytes);s.pendingRaw.clear();s.layer.rawWidth=width;s.layer.rawHeight=height;s.layer.texMatrix[0]=s.layer.texMatrix[5]=s.layer.texMatrix[10]=s.layer.texMatrix[15]=1.f;for(int i:{1,2,3,4,6,7,8,9,11,12,13,14})s.layer.texMatrix[i]=0.f;s.layer.w=width;s.layer.h=height;return true;}
bool GlCompositor::updateRaw(const std::string&id,const uint8_t*pixels,size_t bytes,int width,int height,RawPixelFormat format){if(!pixels||bytes==0||width<=0||height<=0||format==RawPixelFormat::NONE)return false;std::lock_guard<std::mutex>lk(m_);auto &s=sources_[id];s.layer.id=id;applyPendingFilters(s.layer,id);s.external=false;s.layer.external=false;s.layer.rawFormat=format;s.layer.rawWidth=width;s.layer.rawHeight=height;s.pixelW=width;s.pixelH=height;s.pendingRaw.assign(pixels,pixels+bytes);s.pendingRgba.clear();s.layer.w=width;s.layer.h=height;s.layer.texMatrix[0]=s.layer.texMatrix[5]=s.layer.texMatrix[10]=s.layer.texMatrix[15]=1.f;for(int i:{1,2,3,4,6,7,8,9,11,12,13,14})s.layer.texMatrix[i]=0.f;return true;}

void GlCompositor::updateFilterChain(const std::string&id,const int*types,int count,const float*params,int paramCount){
    std::lock_guard<std::mutex>lk(m_);
    auto apply=[&](SourceLayer&layer){
        const int n=std::clamp(std::min(count,paramCount/kFilterStageFloats),0,kMaxFilterStages);
        layer.filterCount=n;
        std::fill(std::begin(layer.filterTypes),std::end(layer.filterTypes),0);
        std::fill(std::begin(layer.filterParams),std::end(layer.filterParams),0.f);
        for(int i=0;i<n;++i)layer.filterTypes[i]=types[i];
        std::copy(params,params+n*kFilterStageFloats,layer.filterParams);
    };
    if(auto*s=source(id))apply(s->layer);
    else{SourceLayer pending;pending.id=id;apply(pending);pendingFilters_[id]=pending;}
}
static std::string lutMapKey(const std::string&id,int slot){return id+"#"+std::to_string(slot);}
void GlCompositor::setLut(const std::string&id,int slot,const std::string&key,int size,const uint8_t*rgb,const float*dmin,const float*dmax){
    if(slot<0||slot>=kMaxLutSlots||size<2||!rgb)return;
    std::lock_guard<std::mutex>lk(m_);
    auto&l=luts_[lutMapKey(id,slot)];
    l.key=key;l.size=size;
    for(int i=0;i<3;++i){l.domainMin[i]=dmin?dmin[i]:0.f;l.domainMax[i]=dmax?dmax[i]:1.f;}
    l.pending.assign(rgb,rgb+static_cast<size_t>(size)*size*size*3);
}
void GlCompositor::clearLut(const std::string&id,int slot){
    std::lock_guard<std::mutex>lk(m_);
    auto it=luts_.find(lutMapKey(id,slot));
    if(it==luts_.end())return;
    if(it->second.texture)lutTexturesToDelete_.push_back(it->second.texture);
    luts_.erase(it);
}
std::string GlCompositor::lutKey(const std::string&id,int slot){
    std::lock_guard<std::mutex>lk(m_);
    auto it=luts_.find(lutMapKey(id,slot));
    return it==luts_.end()?std::string():it->second.key;
}
void GlCompositor::renderTransitionOverlay(int width,int height){if(!overlayProgram_||transitionType_==0||transitionProgress_<=0.001f)return;glUseProgram(overlayProgram_);glBindVertexArray(vao_);glDisable(GL_DEPTH_TEST);glEnable(GL_BLEND);glBlendFunc(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA);GLint c=glGetUniformLocation(overlayProgram_,"uColor");float alpha=transitionProgress_;glUniform4f(c,transitionR_,transitionG_,transitionB_,alpha);glDrawArrays(GL_TRIANGLE_STRIP,0,4);}

void GlCompositor::renderTo(EGLSurface target,int width,int height,int canvasWidth,int canvasHeight){
    if(target==EGL_NO_SURFACE||width<=0||height<=0)return;
    eglMakeCurrent(egl_.display(),target,target,egl_.context());
    glViewport(0,0,width,height);
    glDisable(GL_SCISSOR_TEST);
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA);
    glClearColor(0,0,0,1);
    glClear(GL_COLOR_BUFFER_BIT);

    std::vector<SourceLayer> layers;
    {
        std::lock_guard<std::mutex> lock(m_);
        for(auto& entry:sources_){
            auto& source=entry.second;
            if(source.external&&source.surfaceTexture&&source.updateTex){
                JNIEnv* env=nullptr;
                bool detach=false;
                if(vm_->GetEnv(reinterpret_cast<void**>(&env),JNI_VERSION_1_6)!=JNI_OK){
                    if(vm_->AttachCurrentThread(&env,nullptr)!=JNI_OK)continue;
                    detach=true;
                }
                env->CallVoidMethod(source.surfaceTexture,source.updateTex);
                if(!env->ExceptionCheck()&&source.getMatrix&&source.matrixArray){
                    env->CallVoidMethod(source.surfaceTexture,source.getMatrix,source.matrixArray);
                    jfloat matrix[16];
                    env->GetFloatArrayRegion(static_cast<jfloatArray>(source.matrixArray),0,16,matrix);
                    for(int i=0;i<16;++i)source.layer.texMatrix[i]=matrix[i];
                }
                if(env->ExceptionCheck())env->ExceptionClear();
                if(detach)vm_->DetachCurrentThread();
            }
            if(!source.external&&source.layer.rawFormat==RawPixelFormat::NONE&&!source.pendingRgba.empty()){
                if(!source.layer.textureId)glGenTextures(1,&source.layer.textureId);
                glBindTexture(GL_TEXTURE_2D,source.layer.textureId);
                glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);
                glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
                glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);
                glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
                glPixelStorei(GL_UNPACK_ALIGNMENT,1);
                glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA,source.pixelW,source.pixelH,0,GL_RGBA,GL_UNSIGNED_BYTE,source.pendingRgba.data());
                source.pendingRgba.clear();
            }
            if(!source.external&&source.layer.rawFormat!=RawPixelFormat::NONE&&!source.pendingRaw.empty()){
                glPixelStorei(GL_UNPACK_ALIGNMENT,1);
                const int rawWidth=source.layer.rawWidth;
                const int rawHeight=source.layer.rawHeight;
                if(source.layer.rawFormat==RawPixelFormat::YUYV||source.layer.rawFormat==RawPixelFormat::UYVY){
                    const int textureWidth=(rawWidth+1)/2;
                    if(!source.layer.textureId)glGenTextures(1,&source.layer.textureId);
                    glBindTexture(GL_TEXTURE_2D,source.layer.textureId);
                    glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);
                    glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
                    glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);
                    glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
                    glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA,textureWidth,rawHeight,0,GL_RGBA,GL_UNSIGNED_BYTE,source.pendingRaw.data());
                }else if(source.layer.rawFormat==RawPixelFormat::NV12){
                    const size_t yBytes=static_cast<size_t>(rawWidth)*rawHeight;
                    const int chromaWidth=(rawWidth+1)/2;
                    const int chromaHeight=(rawHeight+1)/2;
                    if(!source.layer.textureId)glGenTextures(1,&source.layer.textureId);
                    glBindTexture(GL_TEXTURE_2D,source.layer.textureId);
                    glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);
                    glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
                    glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);
                    glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
                    glTexImage2D(GL_TEXTURE_2D,0,GL_R8,rawWidth,rawHeight,0,GL_RED,GL_UNSIGNED_BYTE,source.pendingRaw.data());
                    if(!source.layer.auxTextureId)glGenTextures(1,&source.layer.auxTextureId);
                    glBindTexture(GL_TEXTURE_2D,source.layer.auxTextureId);
                    glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);
                    glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
                    glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);
                    glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
                    glTexImage2D(GL_TEXTURE_2D,0,GL_RG8,chromaWidth,chromaHeight,0,GL_RG,GL_UNSIGNED_BYTE,source.pendingRaw.data()+std::min(yBytes,source.pendingRaw.size()));
                }
                source.pendingRaw.clear();
            }
            SourceLayer layer=source.layer;
            if(source.pixelW>0&&source.pixelH>0){layer.rawWidth=layer.rawFormat==RawPixelFormat::NONE?source.pixelW:layer.rawWidth;layer.rawHeight=layer.rawFormat==RawPixelFormat::NONE?source.pixelH:layer.rawHeight;}
            layers.push_back(layer);
        }
        for(GLuint t:lutTexturesToDelete_)glDeleteTextures(1,&t);
        lutTexturesToDelete_.clear();
        for(auto&entry:luts_){
            auto&l=entry.second;
            if(l.pending.empty())continue;
            if(!l.texture)glGenTextures(1,&l.texture);
            glBindTexture(GL_TEXTURE_3D,l.texture);
            glTexParameteri(GL_TEXTURE_3D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);
            glTexParameteri(GL_TEXTURE_3D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
            glTexParameteri(GL_TEXTURE_3D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_3D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_3D,GL_TEXTURE_WRAP_R,GL_CLAMP_TO_EDGE);
            glPixelStorei(GL_UNPACK_ALIGNMENT,1);
            glTexImage3D(GL_TEXTURE_3D,0,GL_RGB8,l.size,l.size,l.size,0,GL_RGB,GL_UNSIGNED_BYTE,l.pending.data());
            l.pending.clear();
            l.pending.shrink_to_fit();
        }
    }
    std::sort(layers.begin(),layers.end(),[](const auto& left,const auto& right){return left.z<right.z;});
    glUseProgram(program_);
    glBindVertexArray(vao_);
    const GLint uExt=glGetUniformLocation(program_,"uExternal"),uRaw=glGetUniformLocation(program_,"uRawFormat"),uCanvas=glGetUniformLocation(program_,"uCanvas"),uRect=glGetUniformLocation(program_,"uRect"),uScale=glGetUniformLocation(program_,"uScale"),uPivot=glGetUniformLocation(program_,"uPivot"),uRot=glGetUniformLocation(program_,"uRotation"),uMat=glGetUniformLocation(program_,"uTexMatrix"),uOp=glGetUniformLocation(program_,"uOpacity"),uCrop=glGetUniformLocation(program_,"uCrop"),uFH=glGetUniformLocation(program_,"uFlipH"),uFV=glGetUniformLocation(program_,"uFlipV"),uRawSize=glGetUniformLocation(program_,"uRawSize"),uExtTex=glGetUniformLocation(program_,"uExtTex"),u2D=glGetUniformLocation(program_,"u2DTex"),uRawTex=glGetUniformLocation(program_,"uRawTex"),uRawAux=glGetUniformLocation(program_,"uRawAuxTex");
    const GLint uStageCount=glGetUniformLocation(program_,"uStageCount"),uStageType=glGetUniformLocation(program_,"uStageType"),uStageParams=glGetUniformLocation(program_,"uStageParams");
    const GLint uTexel=glGetUniformLocation(program_,"uTexel"),uLutMin=glGetUniformLocation(program_,"uLutMin"),uLutMax=glGetUniformLocation(program_,"uLutMax"),uLutSize=glGetUniformLocation(program_,"uLutSize");
    const GLint uLutTex[kMaxLutSlots]={glGetUniformLocation(program_,"uLut0"),glGetUniformLocation(program_,"uLut1")};
    struct LutView{GLuint texture;int size;float domainMin[3],domainMax[3];};
    std::map<std::string,LutView> lutByKey;
    {std::lock_guard<std::mutex> lock(m_);for(auto&e:luts_){const auto&l=e.second;if(!l.texture)continue;LutView v{l.texture,l.size,{},{}};for(int c=0;c<3;++c){v.domainMin[c]=l.domainMin[c];v.domainMax[c]=l.domainMax[c];}lutByKey.emplace(e.first,v);}}
    for(const auto& layer:layers){
        if(!layer.visible||!layer.textureId)continue;
        glUniform1i(uExt,layer.external?1:0);glUniform1i(uRaw,static_cast<int>(layer.rawFormat));
        glUniform1i(uExtTex,0);glUniform1i(u2D,1);glUniform1i(uRawTex,2);glUniform1i(uRawAux,3);
        glUniform2f(uCanvas,static_cast<float>(canvasWidth),static_cast<float>(canvasHeight));
        glUniform4f(uRect,layer.x,layer.y,layer.w,layer.h);glUniform2f(uScale,layer.scaleX,layer.scaleY);
        glUniform2f(uPivot,layer.pivotX,layer.pivotY);glUniform1f(uRot,layer.rotation*0.01745329252f);glUniformMatrix4fv(uMat,1,GL_FALSE,layer.texMatrix);
        glUniform1f(uOp,std::clamp(layer.opacity,0.f,1.f));glUniform4f(uCrop,layer.cropL,layer.cropT,1-layer.cropR,1-layer.cropB);
        glUniform1i(uStageCount,layer.filterCount);
        if(layer.filterCount>0){
            int types[kMaxFilterStages];
            float lutMin[kMaxLutSlots*3]={},lutMax[kMaxLutSlots*3]={},lutSize[kMaxLutSlots]={2.f,2.f};
            for(int i=0;i<layer.filterCount;++i){
                types[i]=layer.filterTypes[i];
                if(types[i]!=kStageLut)continue;
                const int slot=static_cast<int>(layer.filterParams[i*kFilterStageFloats+1]+0.5f);
                auto it=(slot>=0&&slot<kMaxLutSlots)?lutByKey.find(lutMapKey(layer.id,slot)):lutByKey.end();
                if(it==lutByKey.end()){types[i]=0;continue;} // LUT not loaded yet: pass through
                const LutView&l=it->second;
                glActiveTexture(GL_TEXTURE4+slot);glBindTexture(GL_TEXTURE_3D,l.texture);
                for(int c=0;c<3;++c){lutMin[slot*3+c]=l.domainMin[c];lutMax[slot*3+c]=l.domainMax[c];}
                lutSize[slot]=static_cast<float>(l.size);
            }
            glUniform1i(uLutTex[0],4);glUniform1i(uLutTex[1],5);
            glUniform3fv(uLutMin,kMaxLutSlots,lutMin);glUniform3fv(uLutMax,kMaxLutSlots,lutMax);glUniform1fv(uLutSize,kMaxLutSlots,lutSize);
            glUniform1iv(uStageType,layer.filterCount,types);glUniform4fv(uStageParams,layer.filterCount*4,layer.filterParams);
        }
        {const float tw=layer.rawWidth>0?static_cast<float>(layer.rawWidth):std::max(1.f,layer.w),th=layer.rawHeight>0?static_cast<float>(layer.rawHeight):std::max(1.f,layer.h);glUniform2f(uTexel,1.f/tw,1.f/th);}
        glUniform1i(uFH,layer.flipH);glUniform1i(uFV,layer.flipV);glUniform2f(uRawSize,static_cast<float>(layer.rawWidth),static_cast<float>(layer.rawHeight));
        if(layer.external){glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_EXTERNAL_OES,layer.textureId);}
        else if(layer.rawFormat==RawPixelFormat::NONE){glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_2D,layer.textureId);}
        else{glActiveTexture(GL_TEXTURE2);glBindTexture(GL_TEXTURE_2D,layer.textureId);if(layer.rawFormat==RawPixelFormat::NV12){glActiveTexture(GL_TEXTURE3);glBindTexture(GL_TEXTURE_2D,layer.auxTextureId);}}
        glDrawArrays(GL_TRIANGLE_STRIP,0,4);
    }
    renderTransitionOverlay(width,height);
    glBindVertexArray(0);
    eglSwapBuffers(egl_.display(),target);
}

void GlCompositor::loop(){
    JNIEnv* env=nullptr;
    if(vm_->AttachCurrentThread(&env,nullptr)!=JNI_OK)return;
    auto next=std::chrono::steady_clock::now();
#if __ANDROID_API__ >= 31
    APerformanceHintSession* performanceSession=nullptr;
    if(APerformanceHintManager* manager=APerformanceHint_getManager()){
        const int32_t threadId=static_cast<int32_t>(syscall(SYS_gettid));
        const int64_t targetDuration=1000000000LL/std::clamp(fps_.load(),1,240);
        performanceSession=APerformanceHint_createSession(manager,&threadId,1,targetDuration);
    }
    int targetFrameRate=std::clamp(fps_.load(),1,240);
#endif
    while(running_){
        const auto frameStart=std::chrono::steady_clock::now();
        const int canvasWidth=static_cast<int>(canvasW_.load());
        const int canvasHeight=static_cast<int>(canvasH_.load());
        if(preview_!=EGL_NO_SURFACE&&previewWin_){
            renderTo(preview_,ANativeWindow_getWidth(previewWin_),ANativeWindow_getHeight(previewWin_),canvasWidth,canvasHeight);
        }
        if(encoder_!=EGL_NO_SURFACE&&encoderWin_){
            renderTo(encoder_,ANativeWindow_getWidth(encoderWin_),ANativeWindow_getHeight(encoderWin_),canvasWidth,canvasHeight);
        }
        const auto frameEnd=std::chrono::steady_clock::now();
        renderMs_.store(std::chrono::duration<float,std::milli>(frameEnd-frameStart).count());
        frames_++;
        const int frameRate=std::clamp(fps_.load(),1,240);
#if __ANDROID_API__ >= 31
        if(performanceSession){
            if(frameRate!=targetFrameRate){
                targetFrameRate=frameRate;
                APerformanceHint_updateTargetWorkDuration(performanceSession,1000000000LL/frameRate);
            }
            const auto workNs=std::chrono::duration_cast<std::chrono::nanoseconds>(frameEnd-frameStart).count();
            if(workNs>0)APerformanceHint_reportActualWorkDuration(performanceSession,workNs);
        }
#endif
        next+=std::chrono::nanoseconds(1000000000LL/frameRate);
        if(next<frameEnd){dropped_++;next=frameEnd;}
        std::this_thread::sleep_until(next);
    }
#if __ANDROID_API__ >= 31
    if(performanceSession)APerformanceHint_closeSession(performanceSession);
#endif
    vm_->DetachCurrentThread();
}bool GlCompositor::start(){if(running_.exchange(true))return true;thread_=std::thread(&GlCompositor::loop,this);return true;}
void GlCompositor::stop(){if(!running_.exchange(false))return;if(thread_.joinable())thread_.join();}
void GlCompositor::shutdown(){stop();if(egl_.display()!=EGL_NO_DISPLAY){eglMakeCurrent(egl_.display(),egl_.surface(),egl_.surface(),egl_.context());for(auto&kv:sources_){auto&s=kv.second;if(s.layer.textureId)glDeleteTextures(1,&s.layer.textureId);if(s.layer.auxTextureId)glDeleteTextures(1,&s.layer.auxTextureId);}sources_.clear();for(auto&l:luts_)if(l.second.texture)glDeleteTextures(1,&l.second.texture);luts_.clear();for(GLuint t:lutTexturesToDelete_)glDeleteTextures(1,&t);lutTexturesToDelete_.clear();if(program_)glDeleteProgram(program_),program_=0;if(overlayProgram_)glDeleteProgram(overlayProgram_),overlayProgram_=0;if(vbo_)glDeleteBuffers(1,&vbo_),vbo_=0;if(vao_)glDeleteVertexArrays(1,&vao_),vao_=0;}destroyWindow(preview_,previewWin_);destroyWindow(encoder_,encoderWin_);egl_.shutdown();}
}
