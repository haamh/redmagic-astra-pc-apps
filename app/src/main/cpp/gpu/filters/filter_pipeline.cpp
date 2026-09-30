#include "filter_pipeline.h"

namespace stream4k60 {

namespace {
const char* kFullscreenVs = R"GLSL(
#version 320 es
layout(location=0) in vec2 aPos;
layout(location=1) in vec2 aUv;
out vec2 vUv;
void main(){ vUv=aUv; gl_Position=vec4(aPos,0.0,1.0); }
)GLSL";
const char* kCopyFs = R"GLSL(
#version 320 es
precision highp float;
in vec2 vUv;
uniform sampler2D uTexture;
out vec4 fragColor;
void main(){ fragColor=texture(uTexture,vUv); }
)GLSL";
}

GLuint GpuFilter::compileShader(const char* vertexSrc, const char* fragmentSrc) {
    auto compile = [](GLenum type, const char* src) -> GLuint {
        GLuint shader = glCreateShader(type);
        glShaderSource(shader, 1, &src, nullptr);
        glCompileShader(shader);
        GLint ok = GL_FALSE;
        glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
        if (!ok) { glDeleteShader(shader); return 0; }
        return shader;
    };
    GLuint vs = compile(GL_VERTEX_SHADER, vertexSrc), fs = compile(GL_FRAGMENT_SHADER, fragmentSrc);
    if (!vs || !fs) { if (vs) glDeleteShader(vs); if (fs) glDeleteShader(fs); return 0; }
    GLuint program = glCreateProgram();
    glAttachShader(program, vs); glAttachShader(program, fs); glLinkProgram(program);
    GLint ok = GL_FALSE; glGetProgramiv(program, GL_LINK_STATUS, &ok);
    glDeleteShader(vs); glDeleteShader(fs);
    if (!ok) { glDeleteProgram(program); return 0; }
    return program;
}

void GpuFilter::ensureFbo(uint32_t width, uint32_t height) {
    if (fbo_ && outputTexture_) {
        GLint existingW = 0, existingH = 0;
        glBindTexture(GL_TEXTURE_2D, outputTexture_);
        glGetTexLevelParameteriv(GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH, &existingW);
        glGetTexLevelParameteriv(GL_TEXTURE_2D, 0, GL_TEXTURE_HEIGHT, &existingH);
        if (existingW == static_cast<GLint>(width) && existingH == static_cast<GLint>(height)) return;
        glDeleteFramebuffers(1, &fbo_); glDeleteTextures(1, &outputTexture_); fbo_ = outputTexture_ = 0;
    }
    glGenTextures(1, &outputTexture_);
    glBindTexture(GL_TEXTURE_2D, outputTexture_);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, static_cast<GLsizei>(width), static_cast<GLsizei>(height), 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glGenFramebuffers(1, &fbo_);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo_);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, outputTexture_, 0);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
}

FilterPipeline::FilterPipeline() = default;
FilterPipeline::~FilterPipeline() = default;
void FilterPipeline::addFilter(std::shared_ptr<GpuFilter> filter) { if (filter) { filter->initialize(); filters_.push_back(std::move(filter)); } }
void FilterPipeline::removeFilter(int index) { if (index >= 0 && index < static_cast<int>(filters_.size())) filters_.erase(filters_.begin() + index); }
void FilterPipeline::moveFilter(int fromIndex, int toIndex) {
    if (fromIndex < 0 || toIndex < 0 || fromIndex >= static_cast<int>(filters_.size()) || toIndex >= static_cast<int>(filters_.size()) || fromIndex == toIndex) return;
    auto filter = filters_[fromIndex]; filters_.erase(filters_.begin() + fromIndex); filters_.insert(filters_.begin() + toIndex, std::move(filter));
}
void FilterPipeline::clear() { filters_.clear(); }
GLuint FilterPipeline::apply(GLuint inputTexture, uint32_t width, uint32_t height) {
    GLuint current = inputTexture;
    for (const auto& filter : filters_) if (filter && filter->isEnabled()) current = filter->apply(current, width, height);
    return current;
}

} // namespace stream4k60
