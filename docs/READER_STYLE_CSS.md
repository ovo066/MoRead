# 阅读样式 CSS

在「高亮规则」或「标题样式」的 CSS 页签填写声明，无需选择器和大括号。上方预览与阅读正文使用同一套绘制。保存后样式随规则、标题预设和主题保留。

## 渐变文字

```css
background: linear-gradient(90deg, #c65f76 0%, #9b65b8 45%, #567bce 100%);
background-clip: text;
color: transparent;
```

也支持 `-webkit-background-clip: text` 和 `-webkit-text-fill-color: transparent`。

## 渐变背景

```css
color: #34323b;
background: linear-gradient(to right, #fbe4df, #dce8f5);
```

若文字与背景都需要渐变，可以用阅读器扩展的 `color: linear-gradient(...)`：

```css
color: linear-gradient(90deg, #b83b61, #3259b5);
background: linear-gradient(180deg, #fff8e8, #dce8f580);
```

支持 2～16 个色标；位置为 0%～100%，省略时均匀分配。方向可以用 `deg` 或 `to right`、`to bottom` 等写法；0deg 向上、90deg 向右。不写方向时从上到下。颜色支持十六进制（含透明度）以及逗号分隔的 `rgb()` / `rgba()`。

## 图片背景

在 CSS 页签点击「导入背景图片」，再点击图片名称，编辑器会插入：

```css
background-image: url("asset:图片ID");
```

实际 ID 由图片库生成，无需手填。图片居中裁切铺满区域，透明 PNG 可以叠在 `background-color` 上。图片丢失时保留底色。`background-image: none` 清除图片与背景渐变。

## 绘制范围

同一高亮匹配在当前页使用同一个渐变范围，换行不逐字重置；跨页时按各页可见部分重新铺设。标题文字按文字范围渐变，标题背景覆盖包含内边距的标题框。EPUB 高亮仍尊重原书已经指定的颜色和背景，标题样式用于阅读器自己的章首标题。

这是原生阅读器支持的 CSS 声明子集，不支持完整网页布局、选择器、脚本、动画、径向渐变、多层背景、远程图片和内嵌 SVG URL。不支持的声明会显示错误并阻止保存。

## 划线分享模板

「划线与笔记 → 导出卡片」右上角的加号可创建自定义模板。模板会连同名称、纸色、文字色、点缀色、字体、CSS 和语法规则保存在本机；下次进入直接选择，选中后可编辑、另存副本或删除。阅读主题和正文高亮规则不受影响。

分享模板的 CSS 作用于整张纸页和摘录，使用上述声明语法。背景、圆角和边框绘制到纸页；字体、对齐及字形作用于摘录；padding 和 margin-inline 为左右留白，margin-top/bottom 为摘录上下留白，以默认摘录字号为 em 基准。另支持 line-height: 1.7（范围 1～2.5）和 letter-spacing: 0.02em（范围 -0.05～0.3em）。语法规则只匹配摘录文字，沿用成对符号或正则匹配；图片和字体从现有资源库引用。预览和 PNG 导出使用相同的原生绘制函数。
