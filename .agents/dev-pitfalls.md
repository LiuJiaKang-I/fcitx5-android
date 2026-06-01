# 代码开发踩坑记录

## Android UI / 自定义 View

### StaticLayout + Int.MAX_VALUE 的 ALIGN_CENTER 陷阱

使用 `StaticLayout.Builder.obtain(text, start, end, paint, outerWidth)` 构建 layout 时，`ALIGN_CENTER` 对齐方式的内部实现是：文本起始 x = `(outerWidth - lineWidth) / 2`。如果 `outerWidth = Int.MAX_VALUE`，文本会被偏移约 10 亿像素到视口外，导致完全不可见（表现为"空白"）。正确做法：layout 中始终使用 `ALIGN_NORMAL`，水平对齐由 `translateX` 手动处理。

### 覆写 TextView.onDraw() 时必须手动设置 paint.color

`TextView.onDraw()` 在调用 `layout.draw()` 前，会显式执行 `paint.color = mCurTextColor`。这是正常契约——颜色不是持久存储在 paint 上被动使用的，而是每次绘制前从 `mCurTextColor` 主动同步。覆写 `onDraw()` 绕过 `super.onDraw()` 时，必须自己执行 `paint.color = currentTextColor`，否则颜色可能不正确（特别是在主题切换、禁用状态、super.onMeasure 之后）。

### TextView 不存在 setLetterSize / setLetterSpacing 方法

尝试覆写 `TextView` 的 `setLetterSize()` 或 `setLetterSpacing()` 会导致编译失败。`TextView` 设置字号只有 `setTextSize()`，设置字间距是 `setLetterSpacing()`（API 21+ 但 Kotlin 代码中直接覆写会报错）。在自定义 TextView 中监听属性变化，应只覆写父类确实存在的方法。

### Canvas.drawText vs StaticLayout 对 Span 的支持

| 路径 | 能否渲染 Span | 说明 |
|------|:---:|------|
| `Canvas.drawText(String, Paint)` | 否 | 只用 Paint 绘制纯文本，忽略所有 Span |
| `Canvas.drawText(CharSequence, Paint)` | 否 | 最终走相同的 native 绘制路径 |
| `StaticLayout.draw(Canvas)` | 是 | Android 唯一能正确渲染 Spannable 的绘制路径 |

需要支持 SpannableString（如 `ForegroundColorSpan` 多色文本）时，必须使用 `StaticLayout`。

### 覆写 setText/getText 与父类状态脱节

自定义 TextView 时如果用独立字段存储文本而绕过 `super.setText()`，会导致：
- 无障碍服务（TalkBack）读不到最新文本
- `TextWatcher` 不触发
- `textAppearance` 不生效
- 文本选择状态异常

正确做法：`setText()` 中调用 `super.setText()` 将文本存入父类 `mText`，`getText()` 不覆写，让父类作为唯一数据源。

## 利用 TextView.getLayout() 消除自建 StaticLayout

### 方案演进

自定义 TextView 需要获取"文本自然宽度"以实现缩放适配时，有三条路径：

1. **自建 StaticLayout**（方案 A）：用 `StaticLayout.Builder.obtain().build()` 手动创建全宽 Layout 测量。缺点是需要手动同步 paint 状态（覆写 `setTextSize`/`setTypeface` 等），遗漏就会出 bug。

2. **覆写 makeNewLayout()**（方案 B）：控制父类 Layout 的宽度参数。缺点是 `makeNewLayout()` 是 protected 方法，签名在不同 Android 版本间有变化（API 28+ 增加了 `fallbackLineSpacing` 参数），维护成本高。

3. **利用 super.onMeasure() + getLayout()**（方案 B'，推荐）：在 `onMeasure()` 中给 `super.onMeasure()` 传入 `AT_MOST + Int.MAX_VALUE` 的宽度，让父类走正常流程创建全宽 Layout，然后用 `getLayout()` 消费。Paint 状态自动同步，无需手动覆写属性方法。

### 方案 B' 的关键实现

```kotlin
override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    // 让父类用"全宽"创建 Layout，文本不换行
    val wideSpec = MeasureSpec.makeMeasureSpec(Int.MAX_VALUE, MeasureSpec.AT_MOST)
    super.onMeasure(wideSpec, heightMeasureSpec)

    // 现在 getLayout() 可用，是全宽单行 Layout
    val layout = getLayout() ?: return
    val textWidth = layout.getLineWidth(0)

    // 重新计算正确的 measuredDimension
    val desiredWidth = min(ceil(textWidth).toInt() + paddingLeft + paddingRight, widthSize)
    setMeasuredDimension(desiredWidth, measuredHeight)
}

override fun onDraw(canvas: Canvas) {
    val layout = getLayout() ?: return
    canvas.withSave {
        paint.color = currentTextColor
        translate(translateX, translateY)
        scale(textScaleX, textScaleY, 0f, 0f)
        layout.draw(this)
    }
}
```

### 方案 B' 的优势

- 只有一份 Layout，无重复创建
- Paint 状态天然同步（父类创建 Layout 时用的是自己的 paint）
- 新增 Span 类型自动支持
- 不触碰 protected/hidden API，`super.onMeasure()` 是公开 API
- 无需覆写 `setTextSize`/`setTypeface`/`setPadding` 等属性方法

### 注意事项

- `super.onMeasure(AT_MOST, Int.MAX_VALUE)` 之后必须立即 `setMeasuredDimension()` 覆盖掉父类计算的错误尺寸
- 需要实验验证各 Android 版本上 `getLayout()` 的可用性和行为一致性

### 方案 B' 已知陷阱

#### 1. 绕过 super.onDraw() 时必须手动同步 paint.color

`TextView.onDraw()` 在调用 `layout.draw()` 前，会显式执行 `paint.color = mCurTextColor`。这是 TextView 的正常契约——颜色不是持久存储在 paint 上被动使用的，而是每次绘制前从 `mCurTextColor` 主动同步。我们覆写 `onDraw()` 并绕过 `super.onDraw()` 直接调用 `layout.draw()`，因此必须自己执行这个同步：`paint.color = currentTextColor`。

#### 2. ALIGN_CENTER + 宽 Layout 导致 getLineLeft() 巨大偏移

**根因**：`onMeasure()` 中我们给 `super.onMeasure()` 传入 `Int.MAX_VALUE` 宽度，父类内部会将其截断为 `Layout.MAX_WIDTH = 1048576`（2^20）。当 view 的 gravity 是 CENTER 时，Layout 使用 `ALIGN_CENTER` 对齐，内部实现为 `getLineLeft(0) = (layoutWidth - lineWidth) / 2`。对于 52px 的文字，`getLineLeft(0) ≈ (1048576 - 52) / 2 ≈ 524262px`，文字被绘制到屏幕外 ~524K 像素处，完全不可见。

**诊断特征**：候选词 View 宽度正常但文字不可见，且 `paint.color` 和 `currentTextColor` 都正常。

**修复**：在 `onDraw()` 的 `layout.draw()` 之前添加 `translate(-layout.getLineLeft(0), 0f)`，将文字起始位置校正到 x=0。对于 ALIGN_NORMAL 的 Layout，`getLineLeft(0) == 0`，此操作为 no-op。

```kotlin
override fun onDraw(canvas: Canvas) {
    val layout = getLayout() ?: return
    canvas.withSave {
        translate(translateX, translateY)
        scale(textScaleX, textScaleY, 0f, 0f)
        // 校正 ALIGN_CENTER 产生的巨大水平偏移
        translate(-layout.getLineLeft(0), 0f)
        paint.color = currentTextColor
        layout.draw(this)
    }
}
```

**关键数据点**（来自实际调试日志）：

```
text='啊' layoutW=1048576 lineW=52.0 lineLeft=524262.0 alignment=ALIGN_CENTER
```

#### 3. StaticLayout.draw() 的基线坐标系

`StaticLayout.draw(canvas)` 的内部坐标系中，第一行的 baseline 位于 `getLineBaseline(0)` 而非 y=0。对于字号 20sp 的文字，`lineBaseline ≈ 61`。这与 `Canvas.drawText(text, x, y, paint)` 中 y 参数直接是基线位置的行为不同。在计算 `translateY` 垂直居中时，基于 `layout.height` 居中整个 Layout 是正确的（Layout 高度包含 ascent + descent），无需额外校正基线。
