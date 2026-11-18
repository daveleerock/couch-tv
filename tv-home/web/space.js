// The Space look (home.look "space"): the curve of the Earth seen from above Europe at night, with
// real clouds, sunlight glinting off the sea, a blue and orange edge of atmosphere and the cities'
// lights, below a moving aurora and the big clock (which the Earth partly covers). Drawn here,
// started by app.js only for that look.
//
// The Earth is one WebGL quad the size of the screen: each pixel finds where it meets a sphere whose
// centre sits far below the screen, and reads three maps from space/ (day, night lights, and clouds
// with the sea's roughness; Solar System Scope, CC BY 4.0, see space/CREDITS.md). The sun is behind
// the Earth, high on the left, so Europe is night with a lit edge, and the land turns slowly under
// it. It draws at half the screen's resolution, at most 30 times a second (20 under Lighter home),
// and stops while the home is covered or Home settings is open. A still of the same view
// (space/earth-still.webp) shows until the maps are in, and for good if WebGL is missing.
(function () {
  "use strict";

  var VS = "attribute vec2 p; void main(){ gl_Position = vec4(p, 0.0, 1.0); }";
  function fs(precision) {
    return [
      "precision " + precision + " float;",
      "uniform sampler2D day; uniform sampler2D night; uniform sampler2D brc;",
      "uniform float spin;",
      "uniform vec3 sph;",                          // the sphere: centre x, y and radius, in canvas pixels (y up)
      "const float TILT = -0.12;",                  // the horizon leans a little
      "const float PITCH = 0.25;",                  // Europe faces up toward the viewer
      "void main(){",
      "  vec2 q = (gl_FragCoord.xy - sph.xy) / sph.z; float d2 = dot(q, q);",
      "  vec3 L = normalize(vec3(-0.86, 0.34, -0.80));",   // the sun: behind the Earth, high on the left
      "  if (d2 > 1.0) {",
      // the atmosphere's glow past the edge: blue where the sun is, orange at the twilight
      "    float d = sqrt(d2); vec3 nn = normalize(vec3(q, 0.0)); float k = dot(nn, L);",
      "    vec3 ac = mix(vec3(1.0, 0.42, 0.16), vec3(0.32, 0.62, 1.0), smoothstep(-0.1, 0.6, k));",
      "    float a = exp(-(d - 1.0) * 45.0) * smoothstep(-0.55, 0.25, k);",
      "    gl_FragColor = vec4(ac * 1.2, clamp(a, 0.0, 1.0));",
      "    return;",
      "  }",
      "  vec3 n = vec3(q, sqrt(1.0 - d2));",
      "  float ct = cos(TILT), st = sin(TILT), cp = cos(PITCH), sp = sin(PITCH);",
      "  vec3 b = vec3(n.x * ct + n.y * st, -n.x * st + n.y * ct, n.z);",
      "  vec3 a = vec3(b.x, b.y * cp + b.z * sp, -b.y * sp + b.z * cp);",
      "  float lon = atan(a.x, a.z) + spin, lat = asin(clamp(a.y, -1.0, 1.0));",
      "  vec2 uv = vec2(fract(lon / 6.2831853 + 0.5), 0.5 - lat / 3.1415927);",
      "  vec3 dc = pow(texture2D(day, uv).rgb, vec3(2.2)); vec3 nc = pow(texture2D(night, uv).rgb, vec3(2.2));",
      "  vec3 m = texture2D(brc, uv).rgb;",
      "  float cl = smoothstep(0.15, 0.85, m.b), rough = m.g;",
      "  float k = dot(n, L), lit = max(k, 0.0), dayAmt = smoothstep(-0.08, 0.25, k);",
      "  vec3 col = dc * lit * 1.25;",
      "  col = mix(col, vec3(1.0) * (lit * 1.05 + 0.01), cl * 0.92);",                  // clouds
      "  vec3 R = reflect(-L, n);",
      "  col += vec3(1.0, 0.9, 0.75) * pow(max(R.z, 0.0), 36.0) * (1.0 - rough) * (1.0 - cl) * dayAmt * 1.4;",   // the sea's glint
      "  col += nc * vec3(1.0, 0.78, 0.5) * 2.6 * (1.0 - cl * 0.85) * (1.0 - smoothstep(-0.18, 0.06, k));",     // city lights
      "  float f = pow(1.0 - n.z, 4.0);",   // the haze thickens only near the very edge
      "  vec3 atm = mix(vec3(1.0, 0.42, 0.16), vec3(0.32, 0.6, 1.0), smoothstep(-0.15, 0.7, k));",
      "  col = mix(col, atm * (0.10 + smoothstep(-0.35, 0.5, k)), clamp(f * 0.8, 0.0, 1.0) * smoothstep(-0.6, 0.1, k));",
      "  col += vec3(0.02, 0.04, 0.09) * (1.0 - dayAmt) * f;",
      "  col = 1.0 - exp(-col * 1.25);",                                                // soft highlights
      "  gl_FragColor = vec4(pow(col, vec3(1.0 / 2.2)), 1.0);",
      "}"
    ].join("\n");
  }

  var gl, prog, uSpin, uSph, raf = 0, last = 0, running = false, t0 = Date.now(), canvas, root, imgs = 0;
  var TURN_MS = 600000;          // one turn every ten minutes
  var SCALE = 0.5;               // canvas pixels per CSS pixel: the motion is slow, the half size is not seen
  var SPIN0 = 0.26;              // where it starts: Europe in front
  var FRAME_MS = 33;
  // The sphere in CSS pixels on a 1920 x 1080 screen: its centre below the screen, its top edge just
  // above the middle (the same view as space/earth-still.webp)
  var CX = 960, CY = 1650, RAD = 1100;

  function shader(type, src) {
    var s = gl.createShader(type);
    gl.shaderSource(s, src); gl.compileShader(s);
    if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s));
    return s;
  }

  function texture(unit, src, done) {
    var tx = gl.createTexture(), im = new Image();
    im.onload = function () {
      gl.activeTexture(gl.TEXTURE0 + unit);
      gl.bindTexture(gl.TEXTURE_2D, tx);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGB, gl.RGB, gl.UNSIGNED_BYTE, im);
      // powers of two: mipmaps keep the land from shimmering as it turns
      gl.generateMipmap(gl.TEXTURE_2D);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR_MIPMAP_LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.REPEAT);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
      done();
    };
    im.onerror = function () { still(); };
    im.src = src;
  }

  function size() {
    var r = canvas.getBoundingClientRect();
    var w = Math.max(1, Math.round(r.width * SCALE)), h = Math.max(1, Math.round(r.height * SCALE));
    if (canvas.width !== w || canvas.height !== h) { canvas.width = w; canvas.height = h; }
    gl.viewport(0, 0, w, h);
    var k = w / 1920;   // the view is laid out for 1920 wide; WebGL's y runs up from the bottom
    gl.uniform3f(uSph, CX * k, h - CY * k, RAD * k);
  }

  function draw(now) {
    raf = 0;
    if (!running) return;
    if (now - last >= FRAME_MS) {
      last = now;
      gl.uniform1f(uSpin, SPIN0 + ((Date.now() - t0) % TURN_MS) / TURN_MS * 6.2831853);
      gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
    }
    raf = requestAnimationFrame(draw);
  }

  // the still view stays (it is under the canvas anyway); the canvas goes
  function still() {
    gl = null; running = false;
    if (canvas) canvas.style.display = "none";
  }

  function start(el, opts) {
    root = el;
    opts = opts || {};
    var base = opts.base || "space/";
    if (opts.lighter) FRAME_MS = 50;
    canvas = root.querySelector(".space-globe");
    try {
      gl = canvas.getContext("webgl", { alpha: true, premultipliedAlpha: false, antialias: false }) ||
        canvas.getContext("experimental-webgl");
    } catch (e) { gl = null; }
    if (!gl) return still();
    // the night lights in 4K where the TV takes a texture that big, else 2K
    var big = (gl.getParameter(gl.MAX_TEXTURE_SIZE) || 0) >= 4096;
    var hp = gl.getShaderPrecisionFormat && gl.getShaderPrecisionFormat(gl.FRAGMENT_SHADER, gl.HIGH_FLOAT);
    try {
      prog = gl.createProgram();
      gl.attachShader(prog, shader(gl.VERTEX_SHADER, VS));
      gl.attachShader(prog, shader(gl.FRAGMENT_SHADER, fs(hp && hp.precision > 0 ? "highp" : "mediump")));
      gl.linkProgram(prog);
      if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) throw new Error("link");
    } catch (e) { return still(); }
    gl.useProgram(prog);
    var buf = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, buf);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
    var ap = gl.getAttribLocation(prog, "p");
    gl.enableVertexAttribArray(ap);
    gl.vertexAttribPointer(ap, 2, gl.FLOAT, false, 0, 0);
    gl.uniform1i(gl.getUniformLocation(prog, "day"), 0);
    gl.uniform1i(gl.getUniformLocation(prog, "night"), 1);
    gl.uniform1i(gl.getUniformLocation(prog, "brc"), 2);
    uSpin = gl.getUniformLocation(prog, "spin");
    uSph = gl.getUniformLocation(prog, "sph");
    gl.clearColor(0, 0, 0, 0);
    gl.enable(gl.BLEND);
    gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);
    size();
    window.addEventListener("resize", function () { if (gl) { size(); last = 0; } });
    function loaded() { if (++imgs === 3) { root.classList.add("ready"); resume(); } }
    texture(0, base + "earth-day.jpg", loaded);
    texture(1, base + (big ? "earth-night-4k.jpg" : "earth-night.jpg"), loaded);
    texture(2, base + "earth-clouds.jpg", loaded);
  }

  // the home is covered (an app, the screensaver) or Home settings is open: nothing to draw
  function pause() { running = false; if (raf) { cancelAnimationFrame(raf); raf = 0; } }
  function resume() {
    if (!gl || imgs < 3 || running) return;
    running = true; last = 0;
    raf = requestAnimationFrame(draw);
  }

  window.SpaceLook = { start: start, pause: pause, resume: resume };
})();
