package app.majid.adous.db.service;

final class SqlServerTypeFormatter {

    private SqlServerTypeFormatter() {
        // utility
    }

    /**
     * Formats a data type with its length, precision, and scale.
     *
     * @param dataType The base data type
     * @param maxLength The maximum length (for string types)
     * @param precision The precision (for numeric types)
     * @param scale The scale (for numeric types)
     * @return Formatted data type string
     */
    static String formatDataType(String dataType, int maxLength, int precision, int scale) {
        if (dataType == null) {
            return null;
        }
        return switch (dataType.toLowerCase()) {
            case "varchar", "char", "varbinary", "binary" ->
                    dataType + "(" + (maxLength == -1 ? "MAX" : maxLength) + ")";
            case "nvarchar", "nchar" -> {
                int actualLength = maxLength == -1 ? -1 : maxLength / 2;
                yield dataType + "(" + (actualLength == -1 ? "MAX" : actualLength) + ")";
            }
            case "decimal", "numeric" -> dataType + "(" + precision + ", " + scale + ")";
            case "datetime2", "time", "datetimeoffset" ->
                    scale > 0 ? dataType + "(" + scale + ")" : dataType;
            default -> dataType;
        };
    }
}
