package net.fathom.optimizations.core.ui.layout;

/** Small deterministic grid arithmetic shared by the Hub and its layout tests. */
public final class ResponsiveGrid {

    private ResponsiveGrid() {}

    public static int columns(float width, float minimumCellWidth, float gap, int maximumColumns) {
        if (width <= 0f || minimumCellWidth <= 0f || maximumColumns < 1) {
            return 1;
        }
        int fit = (int) Math.floor((width + Math.max(0f, gap))
                / (minimumCellWidth + Math.max(0f, gap)));
        return Math.max(1, Math.min(maximumColumns, fit));
    }

    public static int rows(int itemCount, int columns) {
        if (itemCount <= 0) {
            return 0;
        }
        int safeColumns = Math.max(1, columns);
        return (itemCount + safeColumns - 1) / safeColumns;
    }

    public static float cellWidth(float width, float gap, int columns) {
        int safeColumns = Math.max(1, columns);
        return Math.max(0f, (width - Math.max(0f, gap) * (safeColumns - 1)) / safeColumns);
    }

    public static float contentHeight(int itemCount, int columns, float cellHeight, float gap) {
        int rows = rows(itemCount, columns);
        return rows == 0 ? 0f : rows * cellHeight + (rows - 1) * Math.max(0f, gap);
    }
}
