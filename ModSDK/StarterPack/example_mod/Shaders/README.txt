Shader overrides. Files here replace the engine shader with the same name:

  vulkan/blinn_phong.frag    GLSL; compiled to SPIR-V with glslc when the game starts (or ship blinn_phong.frag.spv)
  metal/blinn_phong.metal    Metal Shading Language; compiled by the driver

Start from a copy of the engine's own shader (src/main/resources/shaders/ in the engine source) and keep its
inputs, uniform / push-constant blocks and entry points unchanged. If an override fails to compile, the engine
logs the error and keeps using its built-in shader.
