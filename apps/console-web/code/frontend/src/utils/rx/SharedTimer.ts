import { Subject, Observable, timer, Subscription } from 'rxjs';

export class SharedTimer implements Disposable {
  static start(dueTime = 0, period = 1000): SharedTimer {
    return new SharedTimer(dueTime, period);
  }

  private readonly subject = new Subject<number>();
  private readonly subscription: Subscription;

  public readonly $: Observable<number> = this.subject.asObservable();

  private constructor(dueTime = 0, period = 1000) {
    this.subscription = timer(dueTime, period).subscribe({
      next: (value) => this.subject.next(value),
      error: (err) => this.subject.error(err),
      complete: () => this.subject.complete(),
    });
  }

  [Symbol.dispose](): void {
    this.subscription.unsubscribe();
    this.subject.complete();
  }
}
