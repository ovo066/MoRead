# Synthetic MDX/MDD fixtures

`sample-classical.mdx` adds short synthetic Chinese headwords and a classical phrase, plus a shared English entry for multi-dictionary switching. It includes an MDD stylesheet reference and an entry link; no published dictionary text is used.

Generated with [writemdict](https://github.com/zhansliu/writemdict), independently of the application's reader. Entries contain only tiny test strings (`apple`, `book`, `books`, `hello`, `world`); `books` links to `book`. The MDD contains a CSS snippet and a minimal SVG. No third-party dictionary content is included.

Variants cover v1 uncompressed, v2 zlib, UTF-16, encrypted key-block indexes, and binary resource records. The LZO fixture uses literal-only LZO1X blocks (length prefix, payload and EOF marker), assembled independently of the application's decompressor. Corruption tests modify copies in a temporary directory.
