package cn.logistics.platform.optimization;

import java.util.List;
import java.util.Objects;

public final class RouteComparison {

    private RouteComparison() {}

    public static RouteDifference compareCitySequences(List<String> planned, List<String> matched) {
        Objects.requireNonNull(planned, "planned");
        Objects.requireNonNull(matched, "matched");
        if (planned.isEmpty() || matched.isEmpty()) {
            throw new IllegalArgumentException("路线城市序列不能为空。");
        }
        if (!planned.getFirst().equals(matched.getFirst())
                || !planned.getLast().equals(matched.getLast())) {
            throw new IllegalArgumentException("路线差异只能在相同 OD 内比较。");
        }

        int prefix = 0;
        while (prefix < planned.size() && prefix < matched.size()
                && planned.get(prefix).equals(matched.get(prefix))) prefix++;
        if (prefix == planned.size() && prefix == matched.size()) {
            return new RouteDifference(-1, -1, List.of(), List.of(), false);
        }

        int suffix = 0;
        while (suffix < planned.size() - prefix && suffix < matched.size() - prefix
                && planned.get(planned.size() - 1 - suffix)
                        .equals(matched.get(matched.size() - 1 - suffix))) suffix++;
        int planEnd = planned.size() - suffix;
        int actualEnd = matched.size() - suffix;
        return new RouteDifference(prefix, planEnd,
                planned.subList(prefix, planEnd), matched.subList(prefix, actualEnd), true);
    }
}
