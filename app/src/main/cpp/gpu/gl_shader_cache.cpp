#include "gl_shader_cache.h"

namespace stream4k60 {

GLuint ShaderCache::getProgram(const std::string& name) {
    auto it = cache_.find(name);
    return it != cache_.end() ? it->second : 0;
}

bool ShaderCache::compileAndCache(const std::string& name, const char* vsSource, const char* fsSource) {
    GLuint vs = compileShader(GL_VERTEX_SHADER, vsSource);
    GLuint fs = compileShader(GL_FRAGMENT_SHADER, fsSource);
    if (!vs || !fs) return false;
    
    GLuint program = glCreateProgram();
    glAttachShader(program, vs);
    glAttachShader(program, fs);
    glLinkProgram(program);
    GLint linked = GL_FALSE;
    glGetProgramiv(program, GL_LINK_STATUS, &linked);
    glDeleteShader(vs);
    glDeleteShader(fs);
    if (!linked) {
        glDeleteProgram(program);
        return false;
    }
    auto old = cache_.find(name);
    if (old != cache_.end() && old->second) glDeleteProgram(old->second);
    cache_[name] = program;
    return true;
}

GLuint ShaderCache::compileShader(GLenum type, const char* source) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint ok = GL_FALSE;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

} // namespace stream4k60
