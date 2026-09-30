#pragma once
#include <GLES3/gl32.h>
#include <string>
#include <vector>
#include <memory>
#include <unordered_map>

namespace stream4k60 {

class GpuFilter {
public:
    virtual ~GpuFilter() = default;
    
    virtual bool initialize() = 0;
    virtual GLuint apply(GLuint inputTexture, uint32_t width, uint32_t height) = 0;
    virtual void setParameter(const std::string& name, float value) {}
    virtual void setParameter(const std::string& name, int value) {}
    virtual void setParameter(const std::string& name, const float* values, int count) {}
    
    virtual const std::string& getName() const = 0;
    virtual bool isEnabled() const { return enabled_; }
    virtual void setEnabled(bool enabled) { enabled_ = enabled; }

protected:
    bool enabled_ = true;
    GLuint shaderProgram_ = 0;
    GLuint fbo_ = 0;
    GLuint outputTexture_ = 0;
    
    GLuint compileShader(const char* vertexSrc, const char* fragmentSrc);
    void ensureFbo(uint32_t width, uint32_t height);
};

class FilterPipeline {
public:
    FilterPipeline();
    ~FilterPipeline();
    
    void addFilter(std::shared_ptr<GpuFilter> filter);
    void removeFilter(int index);
    void moveFilter(int fromIndex, int toIndex);
    void clear();
    
    // Apply all enabled filters in order, returns final texture
    GLuint apply(GLuint inputTexture, uint32_t width, uint32_t height);
    
    size_t filterCount() const { return filters_.size(); }
    std::shared_ptr<GpuFilter> getFilter(int index) { return filters_[index]; }

private:
    std::vector<std::shared_ptr<GpuFilter>> filters_;
};

} // namespace stream4k60
