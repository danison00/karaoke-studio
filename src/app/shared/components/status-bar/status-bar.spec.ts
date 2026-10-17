import { TestBed } from '@angular/core/testing';
import { StatusBar } from './status-bar';

describe('StatusBar', () => {
  beforeEach(() => TestBed.configureTestingModule({ imports: [StatusBar] }));

  it('shows an honest active DSP indicator only for a connected local source', () => {
    const fixture = TestBed.createComponent(StatusBar);
    fixture.componentRef.setInput('message', 'Processando áudio local');
    fixture.componentRef.setInput('sourceType', 'local');
    fixture.componentRef.setInput('dspReady', true);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('DSP ativo');
    expect(fixture.nativeElement.textContent).toContain('Velocidade 1,00×');
  });

  it('shows the same DSP indicator for a processed YouTube source', () => {
    const fixture = TestBed.createComponent(StatusBar);
    fixture.componentRef.setInput('message', 'Processando áudio YouTube');
    fixture.componentRef.setInput('sourceType', 'youtube');
    fixture.componentRef.setInput('dspReady', true);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('DSP ativo');
    expect(fixture.nativeElement.textContent).toContain('YouTube');
    expect(fixture.nativeElement.textContent).not.toContain('oficial');
  });
});
