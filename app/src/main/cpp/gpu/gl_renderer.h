#pragma once
#include <GLES3/gl32.h>

namespace stream4k60 {

class GlRenderer {
public:
    void drawQuad();
    void renderYuvToRgb(GLuint yTexture, GLuint uTexture, GLuint vTexture);
    void captureScreenshot(int width, int height, void* pixels);
};

} // namespace stream4k60
