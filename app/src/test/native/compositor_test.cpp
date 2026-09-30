// Desktop GPU tests for the native compositor, run on Mesa (llvmpipe) with ./run_compositor_test.sh.
// They render real frames through GlCompositor's shaders and check pixels: orientation, crop,
// nested scenes, filter stages and LUTs.
#define private public
#include "../../main/cpp/gpu/gl_compositor.h"
#undef private
#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

using namespace stream4k60;

static int failures = 0;
static const int W = 100, H = 100;

struct Pixel { int r, g, b, a; };

// Renders the program canvas offscreen (top row first, like the compositor's scene canvases).
static std::vector<uint8_t> renderFrame(GlCompositor& c) {
    eglMakeCurrent(c.egl_.display(), c.egl_.surface(), c.egl_.surface(), c.egl_.context());
    glEnable(GL_BLEND);
    glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    auto layers = c.prepareFrame();
    c.renderSceneTargets(layers);
    static GLuint fbo = 0, tex = 0;
    if (!fbo) {
        glGenTextures(1, &tex);
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, W, H, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
        glGenFramebuffers(1, &fbo);
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
    }
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glViewport(0, 0, W, H);
    glClearColor(0, 0, 0, 1);
    glClear(GL_COLOR_BUFFER_BIT);
    c.drawLayers(layers, "", W, H, true);
    std::vector<uint8_t> out(W * H * 4);
    glReadPixels(0, 0, W, H, GL_RGBA, GL_UNSIGNED_BYTE, out.data());
    return out;
}

static Pixel at(const std::vector<uint8_t>& img, int x, int y) {
    const uint8_t* p = &img[(y * W + x) * 4];
    return {p[0], p[1], p[2], p[3]};
}

static void expect(const char* name, const std::vector<uint8_t>& img, int x, int y, int r, int g, int b, int tolerance = 6) {
    Pixel p = at(img, x, y);
    bool ok = std::abs(p.r - r) <= tolerance && std::abs(p.g - g) <= tolerance && std::abs(p.b - b) <= tolerance;
    std::printf("%s %-46s at (%3d,%3d) got %3d %3d %3d, want %3d %3d %3d\n", ok ? "PASS" : "FAIL", name, x, y, p.r, p.g, p.b, r, g, b);
    if (!ok) failures++;
}

// 2x2 image: top-left red, top-right green, bottom-left blue, bottom-right white (row 0 is the top).
static const uint8_t kQuad[] = {255, 0, 0, 255, 0, 255, 0, 255, 0, 0, 255, 255, 255, 255, 255, 255};

static void layer(GlCompositor& c, const std::string& id, float x, float y, float w, float h, int z,
                  float cropT = 0, float opacity = 1, float rot = 0, float pivotX = 0, float pivotY = 0) {
    c.updateLayer(id, x, y, w, h, pivotX, pivotY, rot, 1, 1, opacity, 0, cropT, 0, 0, true, z, false, false);
}

int main() {
    GlCompositor c;
    c.canvasW_.store(W); c.canvasH_.store(H);
    if (!c.egl_.createOffscreenContext() || !c.setupGl()) {
        std::printf("FAIL could not create a GLES 3.2 context or compile the compositor shaders\n");
        return 1;
    }

    // 1. Orientation of CPU-uploaded images.
    c.updateRgba("img", kQuad, sizeof(kQuad), 2, 2);
    layer(c, "img", 0, 0, W, H, 0);
    auto f = renderFrame(c);
    expect("image top-left is red", f, 25, 25, 255, 0, 0);
    expect("image top-right is green", f, 75, 25, 0, 255, 0);
    expect("image bottom-left is blue", f, 25, 75, 0, 0, 255);

    // 2. Crop top removes the image's top half (fraction of source).
    layer(c, "img", 0, 0, W, H, 0, 0.5f);
    f = renderFrame(c);
    expect("crop top keeps the bottom row", f, 25, 50, 0, 0, 255); // centre of the remaining texel row

    // 3. Filter chain: color correction brightness +1 turns blue white-ish; order matters with a luma key after it.
    layer(c, "img", 0, 0, W, H, 0);
    {
        int types[] = {1};
        float p[16] = {0.5f, 1, 1, 1, 0, 1, 0, 0, 1, 1, 1, 1, 0, 0, 0, 0}; // brightness .5, contrast 1, sat 1, gamma exp 1; hue 0, opacity 1; multiply 1; add 0
        c.updateFilterChain("img", types, 1, p, 16);
    }
    f = renderFrame(c);
    expect("brightness +0.5 on red", f, 25, 25, 255, 128, 128);
    {
        int types[] = {0};
        c.updateFilterChain("img", types, 0, nullptr, 0);
    }

    // 4. LUT stage: an inverting 2^3 LUT turns red into cyan.
    {
        std::vector<uint8_t> lut(2 * 2 * 2 * 3);
        for (int b = 0; b < 2; ++b) for (int g = 0; g < 2; ++g) for (int r = 0; r < 2; ++r) {
            int o = ((b * 2 + g) * 2 + r) * 3;
            lut[o] = r ? 0 : 255; lut[o + 1] = g ? 0 : 255; lut[o + 2] = b ? 0 : 255;
        }
        float dmin[3] = {0, 0, 0}, dmax[3] = {1, 1, 1};
        c.setLut("img", 0, "invert", 2, lut.data(), dmin, dmax);
        int types[] = {kStageLut};
        float p[16] = {1, 0};
        c.updateFilterChain("img", types, 1, p, 16);
        renderFrame(c); // first frame uploads the LUT texture
        f = renderFrame(c);
        expect("LUT inverts red to cyan", f, 25, 25, 0, 255, 255);
        int none[] = {0};
        c.updateFilterChain("img", none, 0, nullptr, 0);
        c.clearLut("img", 0);
    }

    // 5. Nested scene: "child" draws the image into scene canvas "S"; "ref" shows S at half size in the bottom-right.
    c.updateLayer("img", 0, 0, W, H, 0, 0, 0, 1, 1, 1, 0, 0, 0, 0, false, 0, false, false); // hide the direct image
    c.updateRgba("child", kQuad, sizeof(kQuad), 2, 2);
    layer(c, "child", 0, 0, W, H, 0);
    c.setSourceOwner("child", "S");
    c.setSceneTarget("S", W, H);
    c.setSourceSceneRef("ref", "S");
    layer(c, "ref", 50, 50, 50, 50, 1);
    f = renderFrame(c);
    expect("nested scene keeps orientation (red)", f, 60, 60, 255, 0, 0);
    expect("nested scene keeps orientation (green)", f, 90, 60, 0, 255, 0);
    expect("nested scene keeps orientation (blue)", f, 60, 90, 0, 0, 255);
    expect("outside nested scene stays black", f, 25, 25, 0, 0, 0);

    // 6. Semi-transparent content inside a nested scene is not darkened twice.
    layer(c, "child", 0, 0, W, H, 0, 0, 0.5f);
    f = renderFrame(c);
    expect("50% red inside nested scene over black", f, 60, 60, 128, 0, 0, 8);

    // 7. Nested scene inside a nested scene, and a cycle does not hang.
    layer(c, "child", 0, 0, W, H, 0);
    c.setSourceOwner("ref", "T");
    c.setSceneTarget("T", W, H);
    c.setSourceSceneRef("outer", "T");
    layer(c, "outer", 0, 0, W, H, 2);
    f = renderFrame(c);
    expect("scene within scene", f, 60, 60, 255, 0, 0);
    c.setSourceSceneRef("loop", "T");
    layer(c, "loop", 0, 0, 10, 10, 3);
    c.setSourceOwner("loop", "S"); // S -> T -> S
    f = renderFrame(c);
    expect("cyclic nesting still renders", f, 60, 60, 255, 0, 0, 40);

    // 8. Removing scene targets stops drawing them.
    c.retainSceneTargets({});
    f = renderFrame(c);
    expect("released scene canvas is not drawn", f, 60, 60, 0, 0, 0);

    std::printf("%s: %d failure(s)\n", failures ? "FAILED" : "OK", failures);
    return failures ? 1 : 0;
}
