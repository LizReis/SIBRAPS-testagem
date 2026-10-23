import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { DetalheGrupoTerapeutico } from './detalhe-grupo-terapeutico';

describe('DetalheGrupoTerapeutico', () => {
  let fixture: ComponentFixture<DetalheGrupoTerapeutico>;
  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [DetalheGrupoTerapeutico], providers: [provideHttpClient(), provideRouter([])] }).compileComponents();
    fixture = TestBed.createComponent(DetalheGrupoTerapeutico);
  });
  it('deve criar o componente', () => expect(fixture.componentInstance).toBeTruthy());
});
