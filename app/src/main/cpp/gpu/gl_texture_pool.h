#pragma once
#include <GLES3/gl32.h>
#include <vector>

namespace stream4k60 {

class GlTexturePool {
public:
    GLuint acquireTexture(uint32_t width, uint32_t height, GLenum format);
    void releaseTexture(GLuint texture);
    
private:
    std::vector<GLuint> freeTextures_;
};

} // namespace stream4k60
