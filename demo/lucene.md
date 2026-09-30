---
title: Apache Lucene
tags: [search, java, index]
category: tools
author: Demo
---
# Apache Lucene

Lucene — библиотека полнотекстового поиска на Java. Индекс состоит из сегментов; запись идёт через `IndexWriter`, а читатели получают свежий срез через `SearcherManager`.

## Анализаторы

Токенизация, приведение к нижнему регистру, удаление стоп-слов и стемминг (Snowball) для русского и английского.
