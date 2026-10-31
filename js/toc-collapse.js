// Loaded by docs/overrides/main.html when extra.toc_collapse is true in
// mkdocs.yml. Right-hand table of contents: an arrow button on each
// top-level entry that has subsections. layout.css collapses every
// top-level entry except the one holding the active link. Clicking a
// button overrides that for one entry. The override lasts until the
// active top-level entry changes, then scrolling takes over again
(function () {
  var toc = document.querySelector('.md-sidebar--secondary [data-md-component="toc"]');
  if (!toc) return;
  toc.classList.add('eca-toc-collapse');

  var items = Array.prototype.filter.call(toc.children, function (item) {
    return item.querySelector(':scope > .md-nav');
  });

  items.forEach(function (item) {
    var link = item.querySelector(':scope > .md-nav__link');
    var button = document.createElement('button');
    button.type = 'button';
    button.className = 'eca-toc-toggle';
    button.setAttribute('aria-label', 'Toggle ' + link.textContent.trim());
    // layout.css draws an arrow that turns down when aria-expanded is true
    button.innerHTML = '<span class="eca-toc-toggle__icon" aria-hidden="true"></span>';
    button.addEventListener('click', function () {
      var open = isExpanded(item);
      item.classList.toggle('eca-toc--open', !open);
      item.classList.toggle('eca-toc--closed', open);
    });
    link.after(button);
  });

  function hasActive(item) {
    return !!item.querySelector('.md-nav__link--active');
  }

  function isExpanded(item) {
    if (item.classList.contains('eca-toc--open')) return true;
    if (item.classList.contains('eca-toc--closed')) return false;
    return hasActive(item);
  }

  var activeItem = items.find(hasActive) || null;

  function sync() {
    var current = items.find(hasActive) || null;
    if (current !== activeItem) {
      activeItem = current;
      items.forEach(function (item) {
        item.classList.remove('eca-toc--open', 'eca-toc--closed');
      });
    }
    items.forEach(function (item) {
      var button = item.querySelector(':scope > .eca-toc-toggle');
      button.setAttribute('aria-expanded', String(isExpanded(item)));
    });
  }

  // Material moves md-nav__link--active as the page scrolls
  new MutationObserver(sync).observe(toc, {
    subtree: true,
    attributes: true,
    attributeFilter: ['class']
  });
  sync();
})();
