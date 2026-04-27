import { useEffect, useState } from 'react';

export const AsyncStatus = {
  Pending: 'pending',
  Fulfilled: 'fulfilled',
  Rejected: 'rejected',
} as const;

export type AsyncState<T> =
  | { readonly status: typeof AsyncStatus.Pending }
  | { readonly status: typeof AsyncStatus.Fulfilled; readonly value: T }
  | { readonly status: typeof AsyncStatus.Rejected; readonly reason: unknown };

export function usePromise<T>(
  factory: () => Promise<T>,
  deps: readonly unknown[],
): AsyncState<T> {
  const [state, setState] = useState<AsyncState<T>>({
    status: AsyncStatus.Pending,
  });

  useEffect(() => {
    let isCancelled = false;

    factory()
      .then((value) => {
        if (!isCancelled) {
          setState({ status: AsyncStatus.Fulfilled, value });
        }
      })
      .catch((reason: unknown) => {
        if (!isCancelled) {
          setState({ status: AsyncStatus.Rejected, reason });
        }
      });

    return () => {
      isCancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps,react-x/exhaustive-deps
  }, deps);

  return state;
}
