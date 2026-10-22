# Configuration sidebar navigation planning

## TL;DR

The Configuration sidebar is slightly confusing. The first page, "Introduction", has `# Configuration` as its title, so it reads as the section itself. On narrower windows, that page's table of contents appears inside the sidebar too. The recommended fix is Material's `navigation.indexes` feature: rename `config/introduction.md` to `config/index.md` so that clicking "Configuration" opens it. The cost is that the URL changes from `/config/introduction/` to `/config/`, so the fix requires a redirect from the old URL through the `mkdocs-redirects` plugin. A smaller fix is to rename the page or its nav entry so the two titles stop matching.

Notes from 2026-10-14 on why the Configuration section's sidebar is confusing and how to fix it. Nothing here has been changed yet.

## The problem

Click the "Configuration" tab and the left sidebar shows a "Configuration" heading, then "Introduction", then the other config pages. It is unclear whether "Introduction" is the same page as "Configuration", and whether the pages below it are children of Configuration or siblings of it.

## Why it happens

In `mkdocs.yml`, "Configuration" is a nav section with 19 pages. "Introduction" (`config/introduction.md`) is its first page. Three things make the structure hard to read:

1. **The section heading is not clickable and the pages are not indented under it.** The theme enables `navigation.tabs` and `navigation.sections`. With both, the sidebar shows the active tab's title ("Configuration") as a bold heading and lists its pages at the same indent. The pages are children, but they look like siblings.
2. **Two titles say "Configuration".** `config/introduction.md` starts with `# Configuration`, so the page reads as the section itself instead of one page in it.
3. **The page's table of contents moves into the sidebar on narrower windows.** Below about 1220px, Material hides the right-hand table of contents and nests it in the left sidebar under the active page. Headings like "Installation", "Ways to configure" and "Schema" then appear between "Introduction" and "Providers / Models", which makes the nesting harder to follow.

## Fix options

### Option 1: section index pages (recommended)

Material's `navigation.indexes` feature makes a page the section's own landing page. Clicking "Configuration" in the sidebar opens it, and the separate "Introduction" entry goes away.

Steps:

1. Add `navigation.indexes` to `theme.features` in `mkdocs.yml`.
2. Rename `docs/config/introduction.md` to `docs/config/index.md`.
3. In `nav:`, list it first in the Configuration section with no title:

   ```yaml
   - Configuration:
     - config/index.md
     - Providers / Models: config/models.md
     # ...
   ```

4. Update links to the old page. About 10 files reference it, including `docs/overrides/home.html`, `docs/overrides/install.html`, `README.md`, `docs/features.md` and several `docs/config/*.md` pages.
5. Add a redirect from the old URL (required, see below).

#### Requirement: redirect the old URL

The URL changes from `/config/introduction/` to `/config/`. `eca.dev/config/introduction` is linked from 5 places in the repo and likely from outside it too, so the old URL must redirect to the new one. This needs the `mkdocs-redirects` plugin.

The Docker image CI uses to build the site already includes it. The image's [Dockerfile](https://github.com/editor-code-assistant/docs-image/blob/master/Dockerfile) installs `mkdocs-redirects>=1.0` on top of `squidfunk/mkdocs-material:9.6`. What is missing is the plugin config in `mkdocs.yml`:

```yaml
plugins:
  - search
  - awesome-pages
  - git-revision-date-localized
  - redirects:
      redirect_maps:
        config/introduction.md: config/index.md
```

To preview locally without Docker, add the plugin to the `uvx` command:

```sh
uvx --with mkdocs-material \
    --with mkdocs-awesome-pages-plugin \
    --with mkdocs-git-revision-date-localized-plugin \
    --with mkdocs-redirects \
    mkdocs serve
```

### Option 2: rename to remove the duplicate title

Keep the file and URL. Rename either the nav entry ("Introduction" to "Overview") or the page's `# Configuration` heading so the two titles no longer match.

This removes the duplicate name but not the flat-looking sidebar.
