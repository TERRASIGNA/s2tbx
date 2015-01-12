package org.esa.beam.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Helper class for simplifying lambda expression usage on collections and arrays.
 *
 * @author Cosmin Cara
 */
public final class CollectionHelper {

    /**
     * Returns the first element of the collection that satisfies the given condition, or <code>null</code> if no such
     * element exists. If the condition is <code>null</code>, the first element of the collection is returned.
     *
     * @param collection The collection to be searched
     * @param condition  The condition to be applied
     * @param <T>        The type of the collection elements
     * @return The first element of the collection matching the condition, or <code>null</code>
     */
    public static <T> T firstOrDefault(Collection<T> collection, Predicate<T> condition) {
        T result = null;
        if (collection != null) {
            if (condition != null) {
                result = collection.stream().findFirst().get();
            } else {
                result = collection.iterator().next();
            }
        }
        return result;
    }

    /**
     * Returns the first element of the array that satisfies the given condition, or <code>null</code> if no such
     * element exists. If the condition is <code>null</code>, the first element of the array is returned.
     *
     * @param array     The array to be searched
     * @param condition The condition to be applied
     * @param <T>       The type of the array elements
     * @return The first element of the array matching the condition, or <code>null</code>
     */
    public static <T> T firstOrDefault(T[] array, Predicate<T> condition) {
        T result = null;
        if (array != null) {
            if (condition != null) {
                result = Arrays.stream(array).findFirst().get();
            } else {
                result = array[0];
            }
        }
        return result;
    }

    /**
     * Selects the list of collection elements that satisfy the given filter.
     * If the filter is <code>null</code>, all the collection elements are returned.
     *
     * @param collection The collection to be filtered
     * @param filter     The filter to be applied
     * @param <T>        The type of collection elements
     * @return A list of elements satisfying the filter
     */
    public static <T> List<T> where(Collection<T> collection, Predicate<T> filter) {
        List<T> result = null;
        if (collection != null) {
            if (filter != null) {
                result = where(collection.stream(), filter);
            } else {
                result = (collection instanceof List ? (List<T>) collection : new ArrayList<T>(collection));
            }
        }
        return result;
    }

    /**
     * Selects the list of array elements that satisfy the given filter.
     * If the filter is <code>null</code>, all the array elements are returned.
     *
     * @param array  The array to be filtered
     * @param filter The filter to be applied
     * @param <T>    The type of array elements
     * @return A list of elements satisfying the filter
     */
    public static <T> List<T> where(T[] array, Predicate<T> filter) {
        List<T> result = null;
        if (array != null) {
            if (filter != null) {
                result = where(Arrays.stream(array), filter);
            } else {
                result = Arrays.asList(array);
            }
        }
        return result;
    }

    private static <T> List<T> where(Stream<T> stream, Predicate<T> filter) {
        return stream.filter(filter).collect(Collectors.toList());
    }
}