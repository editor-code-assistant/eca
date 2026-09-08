View an image file so you can see it (screenshots, rendered output, diagrams, photos), e.g. to check that something you generated looks right.

Usage:
- `path` must be an absolute path to a png, jpg, jpeg, gif or webp file.
- The image is returned as image content, not text. For metadata or bytes (dimensions, EXIF, hex) use `shell_command` (`file`, `identify`, `exiftool`, `xxd`) instead.
- Very large images are rejected; downscale or compress them first.
