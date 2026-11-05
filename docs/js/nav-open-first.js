// Loaded by docs/overrides/main.html when extra.nav_open_first is on in
// mkdocs.yml. Left sidebar: clicking a collapsed group opens its first
// page instead of only expanding the group. A group that holds the
// current page, or one that is already open, toggles as usual. Desktop
// layout only, the mobile drawer keeps Material's behavior
(function () {
  var desktop = window.matchMedia('(min-width: 76.25em)');
  var labels = document.querySelectorAll(
    '.md-sidebar--primary .md-nav__item--nested:not(.md-nav__item--section) > label.md-nav__link'
  );

  labels.forEach(function (label) {
    label.addEventListener('click', function (event) {
      var item = label.parentElement;
      var toggle = item.querySelector(':scope > .md-nav__toggle');
      if (!desktop.matches || !toggle || toggle.checked) return;
      if (item.classList.contains('md-nav__item--active')) return;
      var first = item.querySelector(':scope > .md-nav a.md-nav__link[href]');
      if (!first) return;
      event.preventDefault();
      window.location.href = first.href;
    });
  });
})();
