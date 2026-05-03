export function mapValues<K, V1, V2>(
  map: ReadonlyMap<K, V1>,
  valueMapper: (value: V1, key: K) => V2
): ReadonlyMap<K, V2> {
  return new Map(Array.from(map, ([key, value]) => [key, valueMapper(value, key)]));
}

export function associate<T, K, V>(
  entries: Iterable<T>,
  transformer: (entry: T) => [K, V]
): Map<K, V> {
  const result = new Map<K, V>();

  for (const entry of entries) {
    const [key, value] = transformer(entry);
    result.set(key, value);
  }

  return result;
}
