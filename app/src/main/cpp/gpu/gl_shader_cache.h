#pragma once
#include <string>
#include <unordered_map>
#include <GLES3/gl32.h>

namespace stream4k60 {

class ShaderCache {
public:
    GLuint getProgram(const std::string& name);
    bool compileAndCache(const std::string& name, const char* vsSource, const char* fsSource);
    
private:
    std::unordered_map<std::string, GLuint> cache_;
    GLuint compileShader(GLenum type, const char* source);
};

} // namespace stream4k60
