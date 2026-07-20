#version 450
layout(set = 0, binding = 0) uniform sampler2D uTex;
layout(location = 0) in vec2 vUvAlpha;
layout(location = 1) in vec2 vUvRgb;
layout(location = 0) out vec4 outColor;
// VAP stores opacity in the alpha region's red channel; source RGB alpha is intentionally ignored.
// VAP 将不透明度存于 Alpha 区域的红通道；RGB 区域自带的 Alpha 会被有意忽略。
void main() {
  vec4 alphaColor = texture(uTex, vUvAlpha);
  vec4 rgbColor = texture(uTex, vUvRgb);
  outColor = vec4(rgbColor.rgb, alphaColor.r);
}
