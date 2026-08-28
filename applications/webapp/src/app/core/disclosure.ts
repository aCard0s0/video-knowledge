import { signal } from '@angular/core';

/**
 * Which row in a list has its detail open — the message behind an error code, a sync failure.
 *
 * One row at a time, keyed by id. A table cannot nest a `<details>` (a `<tr>` is not valid inside
 * one), so every screen that wants the behaviour writes the same three lines; this is those three
 * lines, once. `isOpen` and `toggle` both tolerate an absent id, because every id on the wire is
 * optional and a row without one simply never opens.
 */
export function rowDisclosure() {
  const open = signal<string | null>(null);
  return {
    isOpen: (id: string | null | undefined) => !!id && open() === id,
    toggle: (id: string | null | undefined) => {
      if (id) open.update((current) => (current === id ? null : id));
    },
  };
}
