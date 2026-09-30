#include "gl_renderer.h"

namespace stream4k60 {

void GlRenderer::drawQuad() {
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
}

void GlRenderer::renderYuvToRgb(GLuint yTexture, GLuint uTexture, GLuint vTexture) {
    // Implementation
}

void GlRenderer::captureScreenshot(int width, int height, void* pixels) {
    glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
}

} // namespace stream4k60
