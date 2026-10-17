import { TestBed } from '@angular/core/testing';
import { PitchControl } from './pitch-control';

describe('PitchControl', () => {
  beforeEach(() => TestBed.configureTestingModule({ imports: [PitchControl] }));

  it('shows the original tone and emits half-semitone changes', () => {
    const fixture = TestBed.createComponent(PitchControl);
    fixture.componentRef.setInput('value', 0);
    fixture.detectChanges();
    const emitted: number[] = [];
    fixture.componentInstance.pitchChange.subscribe((value) => emitted.push(value));

    expect(fixture.nativeElement.textContent).toContain('Tom original');
    fixture.nativeElement.querySelector('[data-testid="pitch-up"]').click();
    fixture.nativeElement.querySelector('[data-testid="pitch-down"]').click();

    expect(emitted).toEqual([0.5, -0.5]);
  });

  it('allows fractional semitone values from the slider', () => {
    const fixture = TestBed.createComponent(PitchControl);
    fixture.detectChanges();
    const emitted: number[] = [];
    fixture.componentInstance.pitchChange.subscribe((value) => emitted.push(value));
    const slider = fixture.nativeElement.querySelector('input[type="range"]') as HTMLInputElement;

    expect(slider.step).toBe('0.5');
    slider.value = '1.5';
    slider.dispatchEvent(new Event('input'));

    expect(emitted).toEqual([1.5]);
  });

  it('formats a half-semitone value with a decimal comma', () => {
    const fixture = TestBed.createComponent(PitchControl);
    fixture.componentRef.setInput('value', 0.5);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('+0,5 semitons');
  });

  it('formats signed values and disables every pitch action when unsupported', () => {
    const fixture = TestBed.createComponent(PitchControl);
    fixture.componentRef.setInput('value', 4);
    fixture.componentRef.setInput('disabled', true);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('+4 semitons');
    expect(Array.from(fixture.nativeElement.querySelectorAll('button, input')).every(
      (control: unknown) => (control as HTMLButtonElement | HTMLInputElement).disabled,
    )).toBe(true);
    expect(fixture.nativeElement.textContent).toContain('Carregue uma mídia para ativar o ajuste de tom');
  });
});
