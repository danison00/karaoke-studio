import { TestBed } from '@angular/core/testing';
import { VolumeControl } from './volume-control';

describe('VolumeControl', () => {
  beforeEach(() => TestBed.configureTestingModule({ imports: [VolumeControl] }));

  it('emits volume as a normalized value and toggles mute', () => {
    const fixture = TestBed.createComponent(VolumeControl);
    fixture.componentRef.setInput('volume', 0.8);
    fixture.componentRef.setInput('muted', false);
    fixture.detectChanges();
    const volumes: number[] = [];
    const muted: boolean[] = [];
    fixture.componentInstance.volumeChange.subscribe((value) => volumes.push(value));
    fixture.componentInstance.mutedChange.subscribe((value) => muted.push(value));

    const slider = fixture.nativeElement.querySelector('input') as HTMLInputElement;
    slider.value = '35';
    slider.dispatchEvent(new Event('input'));
    fixture.nativeElement.querySelector('button').click();

    expect(volumes).toEqual([0.35]);
    expect(muted).toEqual([true]);
    expect(fixture.nativeElement.textContent).toContain('80%');
  });
});
