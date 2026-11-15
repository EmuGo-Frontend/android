package org.emugo.frontend;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import java.util.ArrayList;
import java.util.List;

/** A ScrollView that only ever stops on menu-row boundaries, so no row is cut in half. */
final class RowScrollView extends ScrollView {
  RowScrollView(Context context) {
    super(context);
    setSmoothScrollingEnabled(false);
    setVerticalFadingEdgeEnabled(false);
    setFillViewport(true);
    setVerticalScrollBarEnabled(false);
  }

  private List<View> rows() {
    ArrayList<View> rows = new ArrayList<>();
    if (getChildCount() == 0) return rows;
    View content = getChildAt(0);
    if (!(content instanceof ViewGroup)) return rows;
    ViewGroup group = (ViewGroup) content;
    for (int index = 0; index < group.getChildCount(); index++) {
      View child = group.getChildAt(index);
      if (child.hasFocusable() && child.getVisibility() == VISIBLE) rows.add(child);
    }
    return rows;
  }

  private int maxScroll() {
    if (getChildCount() == 0) return 0;
    return Math.max(0, getChildAt(0).getHeight() - (getHeight() - getPaddingTop()
        - getPaddingBottom()));
  }

  private int top(View row) {
    return row.getTop() + getChildAt(0).getTop();
  }

  private int bottom(View row) {
    return row.getBottom() + getChildAt(0).getTop();
  }

  /** Scroll offset that shows the given content rectangle, snapped to row boundaries. */
  private int target(int rectTop, int rectBottom) {
    int height = getHeight() - getPaddingTop() - getPaddingBottom();
    int current = getScrollY();
    if (rectTop >= current && rectBottom <= current + height) return current;
    List<View> rows = rows();
    if (rows.isEmpty()) return current;
    int max = maxScroll();
    if (rectBottom > current + height) {
      if (rectBottom >= bottom(rows.get(rows.size() - 1))) return max;
      int minimum = rectBottom - height;
      for (View row : rows)
        if (top(row) >= minimum) return Math.min(max, top(row));
      return max;
    }
    if (rectTop <= top(rows.get(0))) return 0;
    int result = 0;
    for (View row : rows)
      if (top(row) <= rectTop) result = top(row);
    return Math.min(max, result);
  }

  @Override
  protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
    if (getChildCount() == 0) return 0;
    return target(rect.top, rect.bottom) - getScrollY();
  }

  /** Scrolls so that {@code row} is visible with up to {@code context} rows above it. */
  void showRow(View row, int context) {
    List<View> rows = rows();
    int index = rows.indexOf(row);
    if (index < 0) return;
    int first = Math.max(0, index - context);
    int wanted = first == 0 ? 0 : top(rows.get(first));
    int height = getHeight() - getPaddingTop() - getPaddingBottom();
    if (bottom(row) > wanted + height) wanted = target(top(row), bottom(row));
    scrollTo(0, Math.max(0, Math.min(maxScroll(), wanted)));
  }

  /** Moves focus by {@code count} rows (negative = up) and keeps the scroll on row boundaries. */
  View jump(int count) {
    List<View> rows = rows();
    if (rows.isEmpty()) return null;
    View focused = findFocus();
    int current = -1;
    for (int index = 0; index < rows.size(); index++)
      if (focused != null && (rows.get(index) == focused || isAncestor(rows.get(index), focused)))
        current = index;
    int target = Math.max(0, Math.min(rows.size() - 1, Math.max(0, current) + count));
    View row = rows.get(target);
    row.requestFocus();
    showRow(row, 3);
    return row;
  }

  private static boolean isAncestor(View parent, View child) {
    for (Object v = child.getParent(); v instanceof View; v = ((View) v).getParent())
      if (v == parent) return true;
    return false;
  }
}
