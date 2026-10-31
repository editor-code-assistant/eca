// Right-hand table of contents: the entry for the URL hash stays
// highlighted after a TOC click, a link to a section or a page load with
// a hash. Material picks the highlighted entry from the scroll position,
// so a heading near the end of the page, which can't scroll to the top,
// would show a later entry or the last one. Once the reader scrolls by
// wheel, touch or keyboard, Material's highlight takes over again
(function () {
  var toc = document.querySelector('.md-sidebar--secondary [data-md-component="toc"]');
  if (!toc) return;

  var ACTIVE = 'md-nav__link--active';
  var links = Array.prototype.slice.call(toc.querySelectorAll('a.md-nav__link[href^="#"]'));

  // Links Material currently marks active, kept up to date from its class
  // changes so they can be restored when the hash entry is released
  var materialActive = links.filter(function (link) {
    return link.classList.contains(ACTIVE);
  });

  // The link for the URL hash while it overrides Material, or null
  var pinned = null;

  var observer = new MutationObserver(function (records) {
    records.forEach(function (record) {
      var link = record.target;
      var index = materialActive.indexOf(link);
      if (link.classList.contains(ACTIVE)) {
        if (index < 0) materialActive.push(link);
      } else if (index >= 0) {
        materialActive.splice(index, 1);
      }
    });
    if (pinned) apply();
  });

  // Set the active class from the pinned link, or from Material's choice
  // when nothing is pinned. Records of these changes are dropped so the
  // observer only tracks Material's own changes
  function apply() {
    links.forEach(function (link) {
      var active = pinned ? link === pinned : materialActive.indexOf(link) >= 0;
      if (link.classList.contains(ACTIVE) !== active) {
        link.classList.toggle(ACTIVE, active);
      }
    });
    observer.takeRecords();
  }

  function pin(hash) {
    pinned = links.find(function (link) {
      return link.getAttribute('href') === hash;
    }) || null;
    apply();
  }

  function release() {
    if (!pinned) return;
    pinned = null;
    apply();
  }

  links.forEach(function (link) {
    observer.observe(link, { attributes: true, attributeFilter: ['class'] });
    // Clicking the entry already in the URL fires no hashchange
    link.addEventListener('click', function () {
      pin(link.getAttribute('href'));
    });
  });

  window.addEventListener('hashchange', function () {
    pin(window.location.hash);
  });

  var scrollKeys = ['ArrowUp', 'ArrowDown', 'PageUp', 'PageDown', 'Home', 'End', ' '];
  window.addEventListener('wheel', release, { passive: true });
  window.addEventListener('touchmove', release, { passive: true });
  window.addEventListener('keydown', function (event) {
    if (scrollKeys.indexOf(event.key) >= 0) release();
  });

  if (window.location.hash) pin(window.location.hash);
})();
