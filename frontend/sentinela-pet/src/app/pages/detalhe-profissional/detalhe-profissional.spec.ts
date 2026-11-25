import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { ProfissionalPayload, ProfissionalService } from '../../services/profissional/profissional-service';
import { UsuarioLogadoService } from '../../services/usuario-logado-service';
import { DetalheProfissional } from './detalhe-profissional';

describe('DetalheProfissional', () => {
  const profissional: ProfissionalPayload = {
    idPublico: 'prof-teste', nome: 'Profissional Teste', email: 'prof@test.local',
    senha: null, tipoUsuario: 'PROFISSIONAL', unidadeAtuacao: 'CAPS_I',
  };
  const buscarPorId = vi.fn(() => of(profissional));

  beforeEach(async () => {
    buscarPorId.mockClear();
    await TestBed.configureTestingModule({
      imports: [DetalheProfissional],
      providers: [
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ id: profissional.idPublico }) } } },
        { provide: ProfissionalService, useValue: { buscarPorId } },
        { provide: UsuarioLogadoService, useValue: { obterUsuarioLogado: () => of({ idPublico: 'admin-teste', nome: 'Admin Teste', email: 'admin@test.local', tipoUsuario: 'ADMINISTRADOR', unidadeAtuacao: 'CAPS_I' }) } },
      ],
    }).compileComponents();
  });

  it('should create e consultar o identificador da rota', () => {
    const fixture = TestBed.createComponent(DetalheProfissional);
    fixture.detectChanges();
    expect(fixture.componentInstance).toBeTruthy();
    expect(buscarPorId).toHaveBeenCalledWith('prof-teste');
    expect(fixture.nativeElement.querySelector('h1')?.textContent).toContain(profissional.nome);
    expect(fixture.nativeElement.querySelector('.btn-edit')).not.toBeNull();
  });
});
