---
title: TF-IDF
tags: [search, ranking]
category: algorithms
author: Demo
---
# TF-IDF

TF-IDF умножает частоту терма в документе на обратную частоту документа. Простой и понятный метод; в Lucene реализован как `ClassicSimilarity`.

```java
Similarity similarity = new ClassicSimilarity();
```
