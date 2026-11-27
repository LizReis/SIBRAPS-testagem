import { provideHttpClient } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { Subject, of } from 'rxjs';
import { GrupoTerapeuticoService, SessaoGrupoPayload } from '../../services/grupo-terapeutico-service';
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

  it('descarta resposta de outra data e limpa resumo em falha', () => {
    const antiga = new Subject<SessaoGrupoPayload[]>();
    const atual = new Subject<SessaoGrupoPayload[]>();
    const listar = vi.spyOn(TestBed.inject(GrupoTerapeuticoService), 'listarSessoes');
    listar.mockReturnValueOnce(antiga.asObservable()).mockReturnValueOnce(of([]))
      .mockReturnValueOnce(atual.asObservable()).mockReturnValueOnce(of([]));
    component.carregar(); component.carregar();
    antiga.next([]); antiga.complete(); expect(component.carregando).toBe(true);
    atual.error(new Error('falha sintética'));
    expect(component.sessoes).toEqual([]); expect(component.proximasSessoes).toEqual([]);
    expect(component.erro).toBeTruthy(); expect(component.carregando).toBe(false);
  });
  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
