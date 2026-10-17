import { Component } from '@angular/core';
import { KaraokePageComponent } from './features/karaoke/karaoke-page.component';

@Component({
  imports: [KaraokePageComponent],
  selector: 'app-root',
  styleUrl: './app.scss',
  templateUrl: './app.html',
})
export class App {}
