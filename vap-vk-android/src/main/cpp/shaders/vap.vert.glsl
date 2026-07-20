#version 450
layout(location = 0) in vec2 inPos;
layout(location = 1) in vec2 inUvAlpha;
layout(location = 2) in vec2 inUvRgb;
layout(location = 0) out vec2 vUvAlpha;
layout(location = 1) out vec2 vUvRgb;
// Position is already in clip space; independent UVs address packed alpha and RGB regions.
// 位置已处于裁剪空间；两组独立 UV 分别寻址打包的 Alpha 与 RGB 区域。
void main() {
  vUvAlpha = inUvAlpha;
  vUvRgb = inUvRgb;
  gl_Position = vec4(inPos, 0.0, 1.0);
}
