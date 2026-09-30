#include "gl_texture_pool.h"

namespace stream4k60 {

GLuint GlTexturePool::acquireTexture(uint32_t width, uint32_t height, GLenum format) {
    GLuint tex;
    glGenTextures(1, &tex);
    return tex;
}

void GlTexturePool::releaseTexture(GLuint texture) {
    glDeleteTextures(1, &texture);
}

} // namespace stream4k60
