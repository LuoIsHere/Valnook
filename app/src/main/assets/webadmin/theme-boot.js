// Resolves light or dark before the first paint, so the page never flashes the wrong colours. A classic script
// loaded from <head>: the server's policy allows no inline script.
(function () {
  var pref = "system";
  try { pref = localStorage.getItem("valnook-theme") || "system"; } catch (e) { /* private window */ }
  var dark = pref === "dark" || (pref === "system" && matchMedia("(prefers-color-scheme: dark)").matches);
  document.documentElement.dataset.theme = dark ? "dark" : "light";
})();
