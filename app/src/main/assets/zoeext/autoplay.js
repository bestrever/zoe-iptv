/* zoe-iptv 内置扩展：在每个页面里自动播放 <video> 并尝试全屏。
 * 策略（按顺序，哪个成用哪个）：
 *  1. video.play() 自动播放；
 *  2. requestFullscreen（App 端 onFullScreen 会收到回调）；
 *  3. 点页面自带的全屏按钮；
 *  4. 兜底：直接把 video 元素 position:fixed 铺满视口。
 * video 可能是懒加载的，每 1.5s 重试一次，最多 40 次（约 1 分钟）。
 */
(function () {
  function go() {
    var v = document.querySelector("video");
    if (!v) return false;

    try { v.muted = false; } catch (e) {}
    try {
      var p = v.play();
      if (p && p.catch) p.catch(function () {});
    } catch (e) {}

    if (!document.fullscreenElement) {
      try {
        var sels = [
          ".vcp-fullscreen-btn",
          '[class*=fullscreen i]',
          '[class*=full-screen i]',
          ".prism-fullscreen-btn",
          ".vjs-fullscreen-control",
          "[aria-label*=全屏]",
          "[title*=全屏]"
        ];
        for (var i = 0; i < sels.length; i++) {
          var b = document.querySelector(sels[i]);
          if (b) { b.click(); break; }
        }
      } catch (e) {}
      try {
        (v.requestFullscreen || v.webkitRequestFullscreen || function () {}).call(v);
      } catch (e) {}
    }

    // 兜底：视频铺满视口（不需要任何权限，一定生效）
    try {
      v.style.setProperty("position", "fixed", "important");
      v.style.setProperty("inset", "0", "important");
      v.style.setProperty("width", "100vw", "important");
      v.style.setProperty("height", "100vh", "important");
      v.style.setProperty("z-index", "2147483647", "important");
      v.style.setProperty("background", "#000", "important");
      v.style.setProperty("object-fit", "contain", "important");
    } catch (e) {}

    return true;
  }

  var n = 0;
  go();
  var t = setInterval(function () {
    if (go() || ++n > 40) clearInterval(t);
  }, 1500);
})();
