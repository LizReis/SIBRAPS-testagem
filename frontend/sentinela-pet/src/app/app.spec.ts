import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideRouter, RouterOutlet } from '@angular/router';
import { App } from './app';

describe('App', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter([])],
    }).compileComponents();
  });

  it('should create the app', () => {
    expect(TestBed.createComponent(App).componentInstance).toBeTruthy();
  });

  it('renderiza o outlet principal para as rotas da aplicação', () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    expect(fixture.debugElement.query(By.directive(RouterOutlet))).not.toBeNull();
  });
});
