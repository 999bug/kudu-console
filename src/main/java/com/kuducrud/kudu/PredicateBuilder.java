package com.kuducrud.kudu;

import com.fasterxml.jackson.databind.JsonNode;
import com.kuducrud.model.FilterSpec;
import com.kuducrud.web.ApiException;
import org.apache.kudu.ColumnSchema;
import org.apache.kudu.Schema;
import org.apache.kudu.client.KuduPredicate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 过滤条件 → Kudu 谓词。
 * <p>Kudu 只支持 AND，且没有 NE/OR 谓词：NE 与 PREFIX 的后缀部分由客户端二次过滤。
 */
public final class PredicateBuilder {

    /** 单列 IN 的值上限。 */
    private static final int MAX_IN_VALUES = 100;

    private PredicateBuilder() {
    }

    public record ClientFilter(String column, String op, List<JsonNode> values) {
    }

    public record Plan(List<KuduPredicate> predicates, List<ClientFilter> clientFilters) {
        public boolean clientFiltered() {
            return !clientFilters.isEmpty();
        }
    }

    public static Plan build(Schema schema, List<FilterSpec> filters) {
        List<KuduPredicate> predicates = new ArrayList<>();
        List<ClientFilter> clientFilters = new ArrayList<>();
        if (filters == null || filters.isEmpty()) {
            return new Plan(predicates, clientFilters);
        }
        for (FilterSpec filter : filters) {
            if (filter == null || filter.column() == null || filter.column().isBlank()) {
                continue;
            }
            String columnName = filter.column().trim();
            ColumnSchema column = schema.getColumn(columnName);
            if (column == null) {
                throw ApiException.badRequest("过滤列不存在：" + columnName);
            }
            String op = filter.op() == null ? "EQ" : filter.op().trim().toUpperCase();
            switch (op) {
                case "EQ" -> predicates.add(KuduPredicate.newComparisonPredicate(column,
                        KuduPredicate.ComparisonOp.EQUAL, requireValue(column, filter.value())));
                case "LT" -> predicates.add(KuduPredicate.newComparisonPredicate(column,
                        KuduPredicate.ComparisonOp.LESS, requireValue(column, filter.value())));
                case "LTE" -> predicates.add(KuduPredicate.newComparisonPredicate(column,
                        KuduPredicate.ComparisonOp.LESS_EQUAL, requireValue(column, filter.value())));
                case "GT" -> predicates.add(KuduPredicate.newComparisonPredicate(column,
                        KuduPredicate.ComparisonOp.GREATER, requireValue(column, filter.value())));
                case "GTE" -> predicates.add(KuduPredicate.newComparisonPredicate(column,
                        KuduPredicate.ComparisonOp.GREATER_EQUAL, requireValue(column, filter.value())));
                case "IN" -> {
                    List<JsonNode> values = filter.values();
                    if (values == null || values.isEmpty()) {
                        throw ApiException.badRequest("列 " + columnName + "：IN 需要 values");
                    }
                    if (values.size() > MAX_IN_VALUES) {
                        throw ApiException.badRequest("列 " + columnName + "：IN 最多 " + MAX_IN_VALUES + " 个值");
                    }
                    List<Object> converted = new ArrayList<>(values.size());
                    for (JsonNode node : values) {
                        converted.add(requireValue(column, node));
                    }
                    predicates.add(KuduPredicate.newInListPredicate(column, converted));
                }
                case "IS_NULL" -> {
                    if (!column.isNullable()) {
                        throw ApiException.badRequest("列 " + columnName + " 不可为空，IS NULL 无意义");
                    }
                    predicates.add(KuduPredicate.newIsNullPredicate(column));
                }
                case "IS_NOT_NULL" -> predicates.add(KuduPredicate.newIsNotNullPredicate(column));
                case "PREFIX" -> {
                    if (column.getType() != org.apache.kudu.Type.STRING
                            && column.getType() != org.apache.kudu.Type.VARCHAR) {
                        throw ApiException.badRequest("列 " + columnName + " 不是字符串类型，不支持前缀匹配");
                    }
                    String prefix = filter.value() == null ? "" : filter.value().asText();
                    // 下界用 GTE(prefix) 缩小扫描，后缀仍由客户端 startsWith 精确判定
                    predicates.add(KuduPredicate.newComparisonPredicate(column,
                            KuduPredicate.ComparisonOp.GREATER_EQUAL,
                            KuduValueConverter.predicateValue(column, filter.value(), columnName)));
                    clientFilters.add(new ClientFilter(columnName, "PREFIX",
                            List.of(com.fasterxml.jackson.databind.node.TextNode.valueOf(prefix))));
                }
                case "NE" -> {
                    requireValue(column, filter.value());
                    // Kudu 无 NE 谓词，只能在客户端剔除
                    clientFilters.add(new ClientFilter(columnName, "NE", List.of(filter.value())));
                }
                default -> throw ApiException.badRequest("不支持的过滤操作：" + op);
            }
        }
        return new Plan(predicates, clientFilters);
    }

    private static Object requireValue(ColumnSchema column, JsonNode node) {
        if (node == null || node.isNull()) {
            throw ApiException.badRequest("列 " + column.getName() + " 的过滤值不能为空（判空请用 IS NULL）");
        }
        return KuduValueConverter.predicateValue(column, node, column.getName());
    }

    /** 客户端二次过滤：返回 true 表示该行应保留。 */
    public static boolean matches(List<ClientFilter> filters, Schema schema, java.util.Map<String, Object> row) {
        for (ClientFilter filter : filters) {
            ColumnSchema column = schema.getColumn(filter.column());
            Object actual = row.get(filter.column());
            JsonNode node = filter.values().get(0);
            Object expected = KuduValueConverter.predicateValue(column, node, filter.column());
            if ("PREFIX".equals(filter.op())) {
                if (actual == null || !String.valueOf(actual).startsWith(String.valueOf(expected))) {
                    return false;
                }
            } else if ("NE".equals(filter.op())) {
                // 与 SQL 语义一致：NULL 不满足 !=
                if (actual == null || valueEquals(actual, expected)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean valueEquals(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a instanceof Number na && b instanceof Number nb) {
            return new BigDecimal(na.toString()).compareTo(new BigDecimal(nb.toString())) == 0;
        }
        return normalize(a).equals(normalize(b));
    }

    private static String normalize(Object value) {
        if (value instanceof byte[] bytes) {
            return Base64.getEncoder().encodeToString(bytes);
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        return String.valueOf(value);
    }
}
