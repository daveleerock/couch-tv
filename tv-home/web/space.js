// The Space look (home.look "space"): the curve of the Earth seen from above Europe at night, its
// city lights below a moving aurora, behind rows of app banners. Drawn here, started by app.js only
// for that look.
//
// The Earth is one WebGL quad the size of the screen: each pixel finds where it meets a sphere whose
// centre sits far below the screen, and reads NASA's Blue Marble (day) and Black Marble (city lights)
// maps, public domain, from space/. The sun is behind the Earth, high on the left, so the planet is
// night with a thin lit edge, and the land turns slowly under it. It draws at half the screen's
// resolution, at most 30 times a second, and stops while the home is covered or Home settings is
// open. Without WebGL (or under Lighter home) a still picture of the same view shows instead
// (space/earth-still.jpg), which is also what shows while the maps load.
(function () {
  "use strict";

  var VS = "attribute vec2 p; void main(){ gl_Position = vec4(p, 0.0, 1.0); }";
  var FS = [
    "precision mediump float;",
    "uniform sampler2D day; uniform sampler2D night;",
    "uniform float spin;",
    "uniform vec3 sph;",                              // the sphere: centre x, y and radius, in canvas pixels (y up)
    "const vec3 L = vec3(-0.35, 0.55, -0.76);",       // the sun: behind the Earth, high on the left
    "const float TILT = -0.18;",                      // the horizon leans a little, top left to bottom right
    "const float PITCH = 0.05;",                      // looking over Europe, not down at the pole
    "void main(){",
    "  vec2 q = (gl_FragCoord.xy - sph.xy) / sph.z; float d2 = dot(q, q);",
    "  vec3 sun = normalize(L);",
    "  if (d2 > 1.0) {",
    "    float d = sqrt(d2);",
    "    float glow = exp(-(d - 1.0) * 60.0) * 0.85;",
    "    float lit = clamp(dot(normalize(vec3(q, 0.0)), sun) * 0.9 + 0.45, 0.0, 1.0);",
    "    gl_FragColor = vec4(vec3(0.33, 0.6, 1.0) * glow * lit, glow * lit);",
    "    return;",
    "  }",
    "  vec3 n = vec3(q, sqrt(1.0 - d2));",
    "  float ct = cos(TILT), st = sin(TILT), cp = cos(PITCH), sp = sin(PITCH);",
    "  vec3 b = vec3(n.x * ct + n.y * st, -n.x * st + n.y * ct, n.z);",
    "  vec3 a = vec3(b.x, b.y * cp + b.z * sp, -b.y * sp + b.z * cp);",
    "  float lon = atan(a.x, a.z) + spin; float lat = asin(clamp(a.y, -1.0, 1.0));",
    "  vec2 uv = vec2(fract(lon / 6.2831853 + 0.5), 0.5 - lat / 3.1415927);",
    "  vec3 dc = texture2D(day, uv).rgb; vec3 nc = texture2D(night, uv).rgb;",
    "  float k = dot(n, sun);",
    "  float dayAmt = smoothstep(-0.06, 0.22, k);",
    // city lights are grey-white in the night map, its land and sea dark blue (red near 0)
    "  vec3 nightCol = nc * 0.10 + vec3(1.0, 0.78, 0.45) * smoothstep(0.10, 0.42, nc.r) * 1.25;",
    "  vec3 col = dc * (0.10 + 1.10 * max(k, 0.0)) * dayAmt + nightCol * (1.0 - dayAmt);",
    "  float rim = pow(1.0 - n.z, 3.0);",
    "  col += vec3(0.30, 0.55, 1.0) * rim * (0.06 + 0.94 * dayAmt) * 0.9;",
    "  col *= 0.55 + 0.45 * smoothstep(0.0, 0.35, n.z);",
    "  gl_FragColor = vec4(col, 1.0);",
    "}"
  ].join("\n");

  var gl, prog, uSpin, uSph, raf = 0, last = 0, running = false, t0 = Date.now(), canvas, root, imgs = 0;
  var TURN_MS = 600000;          // one turn every ten minutes
  var SCALE = 0.5;               // canvas pixels per CSS pixel: the motion is slow, the half size is not seen
  var SPIN0 = 0.30;              // where it starts: Europe in front
  // The sphere in CSS pixels on a 1920 x 1080 screen: its centre below the screen, its top edge a
  // little under the middle (the same view as space/earth-still.jpg)
  var CX = 1300, CY = 1780, RAD = 1334;

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
      // 2048 x 1024 is a power of two: mipmaps keep the land from shimmering as it turns
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
    if (now - last >= 33) {
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

  function stars(c) {
    var r = c.getBoundingClientRect(), w = c.width = Math.round(r.width), h = c.height = Math.round(r.height);
    var x = c.getContext("2d"), seed = 7;
    function rnd() { seed = (seed * 16807) % 2147483647; return seed / 2147483647; }
    x.clearRect(0, 0, w, h);
    for (var i = 0; i < 700; i++) {
      var sx = rnd() * w, sy = rnd() * h * 0.75, m = rnd(), rad = m > 0.985 ? 1.5 : m > 0.9 ? 1.05 : 0.65;
      x.globalAlpha = 0.25 + rnd() * 0.6;
      x.fillStyle = rnd() > 0.8 ? "#cfd9ff" : "#ffffff";
      x.beginPath(); x.arc(sx, sy, rad, 0, 6.2832); x.fill();
    }
    x.globalAlpha = 1;
  }

  function start(el, opts) {
    root = el;
    opts = opts || {};
    var base = opts.base || "space/";
    var sky = root.querySelector(".space-stars");
    if (sky) stars(sky);
    canvas = root.querySelector(".space-globe");
    if (opts.still) return still();   // Lighter home: the still picture only
    try {
      gl = canvas.getContext("webgl", { alpha: true, premultipliedAlpha: false, antialias: false }) ||
        canvas.getContext("experimental-webgl");
    } catch (e) { gl = null; }
    if (!gl) return still();
    try {
      prog = gl.createProgram();
      gl.attachShader(prog, shader(gl.VERTEX_SHADER, VS));
      gl.attachShader(prog, shader(gl.FRAGMENT_SHADER, FS));
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
    uSpin = gl.getUniformLocation(prog, "spin");
    uSph = gl.getUniformLocation(prog, "sph");
    gl.clearColor(0, 0, 0, 0);
    gl.enable(gl.BLEND);
    gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);
    size();
    window.addEventListener("resize", function () { if (gl) { size(); last = 0; } });
    function loaded() { if (++imgs === 2) { root.classList.add("ready"); resume(); } }
    texture(0, base + "earth-day.jpg", loaded);
    texture(1, base + "earth-night.jpg", loaded);
  }

  // the home is covered (an app, the screensaver) or Home settings is open: nothing to draw
  function pause() { running = false; if (raf) { cancelAnimationFrame(raf); raf = 0; } }
  function resume() {
    if (!gl || imgs < 2 || running) return;
    running = true; last = 0;
    raf = requestAnimationFrame(draw);
  }

  window.SpaceLook = { start: start, pause: pause, resume: resume };
})();
