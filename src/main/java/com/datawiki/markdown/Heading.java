package com.datawiki.markdown;

import java.util.List;

/** Node of the document outline: children are the headings nested under this one. */
public record Heading(int level, String text, List<Heading> children) {
}
