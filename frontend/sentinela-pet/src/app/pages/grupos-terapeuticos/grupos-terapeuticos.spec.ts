import { provideHttpClient } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { GruposTerapeuticos } from './grupos-terapeuticos';

describe('GruposTerapeuticos', () => {
  let component: GruposTerapeuticos;
  let fixture: ComponentFixture<GruposTerapeuticos>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [GruposTerapeuticos],
      providers: [provideHttpClient(), provideRouter([])],
    }).compileComponents();

    fixture = TestBed.createComponent(GruposTerapeuticos);
    component = fixture.componentInstance;
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
