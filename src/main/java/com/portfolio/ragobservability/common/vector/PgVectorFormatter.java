package com.portfolio.ragobservability.common.vector;

public final class PgVectorFormatter {

    private PgVectorFormatter() {
    }

    public static String format(float[] vector) {
        if (vector == null || vector.length == 0) {
            throw new IllegalArgumentException("Vector must not be null or empty");
        }
        StringBuilder literal = new StringBuilder(vector.length * 10).append('[');
        for (int index = 0; index < vector.length; index++) {
            float value = vector[index];
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("Vector contains a non-finite value");
            }
            if (index > 0) {
                literal.append(',');
            }
            literal.append(Float.toString(value));
        }
        return literal.append(']').toString();
    }
}
