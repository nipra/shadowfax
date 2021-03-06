(ns shadowfax.filters
  (:import (org.apache.hadoop.hbase.filter ValueFilter
                                           CompareFilter
                                           CompareFilter$CompareOp
                                           SingleColumnValueFilter
                                           PrefixFilter
                                           QualifierFilter
                                           FamilyFilter
                                           FilterList
                                           RowFilter
                                           RandomRowFilter
                                           ColumnRangeFilter
                                           ColumnPrefixFilter
                                           ColumnCountGetFilter
                                           ColumnPaginationFilter
                                           KeyOnlyFilter
                                           FirstKeyOnlyFilter
                                           InclusiveStopFilter)
           (org.apache.hadoop.hbase.filter WritableByteArrayComparable
                                           BinaryComparator
                                           BinaryPrefixComparator
                                           BitComparator
                                           BitComparator$BitwiseOp
                                           RegexStringComparator
                                           SubstringComparator
                                           FilterList$Operator)
           (org.apache.hadoop.hbase.util Bytes)))

(def must-pass-all FilterList$Operator/MUST_PASS_ALL)
(def must-pass-one FilterList$Operator/MUST_PASS_ONE)

(def eq CompareFilter$CompareOp/EQUAL)
(def gt CompareFilter$CompareOp/GREATER)
(def gte CompareFilter$CompareOp/GREATER_OR_EQUAL)
(def lt CompareFilter$CompareOp/LESS)
(def lte CompareFilter$CompareOp/LESS_OR_EQUAL)
(def ne CompareFilter$CompareOp/NOT_EQUAL)

;;; Comparators
(defn bit-comparator
  [value bitwise-op]
  (BitComparator. (Bytes/toBytes value) bitwise-op))

(defn make-bit-comparator
  [bitwise-op]
  (fn [value]
    (bit-comparator value bitwise-op)))

(def binary-and (make-bit-comparator BitComparator$BitwiseOp/AND))
(def binary-or (make-bit-comparator BitComparator$BitwiseOp/OR))
(def binary-xor (make-bit-comparator BitComparator$BitwiseOp/XOR))

(defn binary-comparator
  [value]
  (BinaryComparator. (Bytes/toBytes value)))

(defn binary-prefix-comparator
  [value]
  (BinaryPrefixComparator. (Bytes/toBytes value)))

(defn regex-string-comparator
  [regex]
  (RegexStringComparator. regex))

(defn substring-comparator
  [substr]
  (SubstringComparator. substr))

;;; Filters
(defn column-qualifier-filter
  [qualifier]
  (QualifierFilter. eq (binary-comparator qualifier)))


(defn column-family-filter
  [family]
  (FamilyFilter. eq (binary-comparator family)))

(defn column-filter
  [column-family qualifier]
  (let [qualifier-filter (column-qualifier-filter qualifier)
        family-filter (column-family-filter column-family)]
    (FilterList. [qualifier-filter family-filter])))

;;; Column filters
(defn column-range-filter
  [min-column max-column]
  (ColumnRangeFilter. (Bytes/toBytes min-column) true
                      (Bytes/toBytes max-column) true))

(defn column-prefix-filter
  [prefix]
  (ColumnPrefixFilter. (Bytes/toBytes prefix)))

(defn column-count-get-filter
  [n]
  (ColumnCountGetFilter. n))

(defn column-pagination-filter
  [limit offset]
  (ColumnPaginationFilter. limit offset))

;;; Row filters
(defn row-filter-with-regex
  [regex & {:keys [compare-op]
            :or {compare-op eq}}]
  (RowFilter. compare-op (regex-string-comparator regex)))

(defn row-prefix-filter
  [prefix]
  (PrefixFilter. (Bytes/toBytes prefix)))

(defn row-suffix-filter
  [suffix]
  (row-filter-with-regex (format "^.*%s$" suffix)))

(defn random-row-filter
  [& [n]]
  (RandomRowFilter. (or n (rand))))

;;; Value filters
(defn value-filter
  [value & {:keys [compare-op comparator-fn]
            :or {compare-op eq
                 comparator-fn binary-comparator}}]
  (ValueFilter. compare-op (comparator-fn value)))

(defn column-value-filter
  "NOTE: When using this filter on a Scan with specified inputs, the column to 
   be tested should also be added as input (otherwise the filter will regard the
   column as missing)."
  [column-family qualifier value & {:keys [compare-op comparator-fn]
                                    :or {compare-op eq
                                         comparator-fn binary-comparator}}]
  (SingleColumnValueFilter. (Bytes/toBytes column-family)
                            (Bytes/toBytes qualifier)
                            compare-op
                            (comparator-fn value)))

;;;
(defn key-only-filter
  []
  (KeyOnlyFilter.))

(defn first-key-only-filter
  []
  (FirstKeyOnlyFilter.))

;;;
(defn inclusive-stop-filter
  [stop-row-key]
  (InclusiveStopFilter. (Bytes/toBytes stop-row-key)))

;;;
(defn sanitize-filters
  [filters & [key-only?]]
  (when filters
    (let [filters* (if key-only?
                     (concat filters [(key-only-filter)
                                      (first-key-only-filter)])
                     filters)]
      (if (instance? FilterList filters*)
        filters*
        (FilterList. filters*)))))

;;;
(defn filter-list
  ([filters]
     (filter-list filters must-pass-all))
  ([filters operator]
     (FilterList. operator filters)))
