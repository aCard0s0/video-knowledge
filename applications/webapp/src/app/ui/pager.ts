import { Component, computed, input, output } from '@angular/core';

/** Server-side paging is the only kind here: the corpus runs to thousands of rows. */
@Component({
  selector: 'vk-pager',
  template: `
    <div class="wrap mono">
      <span class="muted">{{ label() }}</span>
      <button class="btn-sm" type="button" [disabled]="page() <= 0" (click)="go.emit(page() - 1)">Previous</button>
      <button class="btn-sm" type="button" [disabled]="!hasNext()" (click)="go.emit(page() + 1)">Next</button>
    </div>
  `,
  styles: `
    .wrap {
      display: flex;
      align-items: center;
      justify-content: flex-end;
      gap: var(--space-sm);
      padding: var(--space-sm) var(--space-md);
      font-size: var(--fs-xs);
    }
  `,
})
export class Pager {
  readonly page = input.required<number>();
  readonly size = input.required<number>();
  readonly total = input<number>(0);
  readonly go = output<number>();

  protected readonly hasNext = computed(() => (this.page() + 1) * this.size() < this.total());

  protected readonly label = computed(() => {
    const total = this.total();
    if (total === 0) return '0 of 0';
    const first = this.page() * this.size() + 1;
    // Past the end for the frame it takes `clampPage` to pull the page back. "26–25 of 25" is not
    // a range, and Previous is enabled here, so the label says which way out.
    if (first > total) return `page ${this.page() + 1} is past the end of ${total}`;
    const last = Math.min(total, (this.page() + 1) * this.size());
    return `${first}–${last} of ${total}`;
  });
}
