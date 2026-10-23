import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router } from '@angular/router';
import { of, Subject, throwError } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import { ProfissionalPayload, ProfissionalService } from '../../services/profissional/profissional-service';
import { UnidadeSaude, UnidadeSaudeService } from '../../services/unidade-saude-service';
import { UsuarioLogadoDTO, UsuarioLogadoService } from '../../services/usuario-logado-service';

import { CadastroProfissional } from './cadastro-profissional';

describe('CadastroProfissional', () => {
  let component: CadastroProfissional;
  let fixture: ComponentFixture<CadastroProfissional>;
  let id: string | null;
  const usfs = [
    { id: 7, cnes: '7', nomeUsf: 'USF vinda da API A' },
    { id: 9, cnes: '9', nomeUsf: 'USF vinda da API B' },
  ] as UnidadeSaude[];
  const profissional: ProfissionalPayload = {
    nome: 'Teste', email: 'teste@test.local', tipoUsuario: 'PROFISSIONAL',
    unidadeAtuacao: 'USF', usfAtuacaoId: 7, senha: null,
  };
  const listarUnidades = vi.fn(() => of(usfs));
  const cadastrar = vi.fn(() => of(profissional));
  const atualizar = vi.fn(() => of(profissional));
  const buscarPorId = vi.fn(() => of(profissional));
  const obterUsuarioLogado = vi.fn(() => of<UsuarioLogadoDTO>({
    idPublico: 'admin', nome: 'Admin', email: 'admin@test.local', tipoUsuario: 'ADMINISTRADOR',
    unidadeAtuacao: 'USF', usfAtuacaoId: 7, usfAtuacao: usfs[0],
  }));

  beforeEach(async () => {
    id = null;
    vi.clearAllMocks();
    listarUnidades.mockReturnValue(of(usfs));
    buscarPorId.mockReturnValue(of(profissional));
    obterUsuarioLogado.mockReturnValue(of({
      idPublico: 'admin', nome: 'Admin', email: 'admin@test.local', tipoUsuario: 'ADMINISTRADOR',
      unidadeAtuacao: 'USF', usfAtuacaoId: 7, usfAtuacao: usfs[0],
    }));
    atualizar.mockReturnValue(of(profissional));
    await TestBed.configureTestingModule({
      imports: [CadastroProfissional],
      providers: [
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: { get: () => id } } } },
        { provide: Router, useValue: { navigate: vi.fn() } },
        { provide: ProfissionalService, useValue: { cadastrar, atualizar, buscarPorId } },
        { provide: UnidadeSaudeService, useValue: { listarUnidades } },
        { provide: UsuarioLogadoService, useValue: { obterUsuarioLogado } },
      ],
    })
    .compileComponents();

  });

  function criar() {
    fixture = TestBed.createComponent(CadastroProfissional);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  function preencher(unidade: string) {
    component.profissionalForm.patchValue({
      nome: 'Teste', email: 'teste@test.local', tipoUsuario: 'PROFISSIONAL',
      senha: 'senha-teste', confirmarSenha: 'senha-teste', unidadeAtuacao: unidade,
    });
  }

  it('should create', () => {
    criar();
    expect(component).toBeTruthy();
  });

  it('preenche e trava somente a USF do administrador sem consultar o catálogo', () => {
    criar();
    const options = Array.from(fixture.nativeElement.querySelectorAll('#unidadeAtuacao option')) as HTMLOptionElement[];
    expect(options.map(o => o.textContent?.trim())).toEqual([
      'Selecione a unidade', usfs[0].nomeUsf,
    ]);
    expect(options.some(o => o.value === 'USF')).toBe(false);
    expect((fixture.nativeElement.querySelector('#unidadeAtuacao') as HTMLSelectElement).disabled).toBe(true);
    expect(component.profissionalForm.getRawValue().unidadeAtuacao).toBe('USF:7');
    expect(listarUnidades).not.toHaveBeenCalled();
  });

  it.each(['CAPS_AD', 'CAPS_I', 'CAPS_II'])('envia %s sem associação USF', unidade => {
    obterUsuarioLogado.mockReturnValue(of({ idPublico: 'admin', nome: 'Admin', email: 'admin@test.local',
      tipoUsuario: 'ADMINISTRADOR', unidadeAtuacao: unidade }));
    criar(); preencher(unidade); component.salvarProfissional();
    expect(component.unidadeAtuacaoOptions).toEqual([{ valor: unidade, label: unidade.replaceAll('_', ' ') }]);
    expect(cadastrar).toHaveBeenCalledWith(expect.objectContaining({ unidadeAtuacao: unidade, usfAtuacaoId: null }));
  });

  it('envia USF com o ID persistido', () => {
    criar(); preencher('USF:7'); component.salvarProfissional();
    expect(cadastrar).toHaveBeenCalledWith(expect.objectContaining({ unidadeAtuacao: 'USF', usfAtuacaoId: 7 }));
  });

  it('aguarda a unidade de /me antes de permitir cadastro', async () => {
    const resposta = new Subject<UsuarioLogadoDTO>();
    obterUsuarioLogado.mockReturnValue(resposta);
    criar();
    expect(component.carregando).toBe(true);
    component.salvarProfissional();
    expect(cadastrar).not.toHaveBeenCalled();
    resposta.next({ idPublico: 'admin', nome: 'Admin', email: 'admin@test.local', tipoUsuario: 'ADMINISTRADOR',
      unidadeAtuacao: 'USF', usfAtuacaoId: 9, usfAtuacao: usfs[1] });
    await fixture.whenStable();
    expect(component.unidadeAtuacaoOptions).toEqual([{ valor: 'USF:9', label: usfs[1].nomeUsf }]);
    expect(component.profissionalForm.controls.unidadeAtuacao.disabled).toBe(true);
  });

  it('não libera cadastro quando /me falha ou a USF não está configurada', () => {
    obterUsuarioLogado.mockReturnValue(throwError(() => new Error('falha')));
    criar(); preencher(''); component.salvarProfissional();
    expect(cadastrar).not.toHaveBeenCalled();
    expect(component.unidadeAtuacaoOptions).toEqual([]);
    fixture.destroy();
    obterUsuarioLogado.mockReturnValue(of({ idPublico: 'admin', nome: 'Admin', email: 'admin@test.local',
      tipoUsuario: 'ADMINISTRADOR', unidadeAtuacao: 'USF', usfAtuacaoId: null }));
    criar();
    expect(component.erroGeral).toContain('ainda não foi configurada');
    expect(component.profissionalForm.controls.unidadeAtuacao.invalid).toBe(true);
  });

  it('pré-seleciona a USF quando a lista chega depois do usuário', () => {
    id = 'usuario-id';
    const resposta = new Subject<UnidadeSaude[]>();
    listarUnidades.mockReturnValue(resposta);
    criar();
    expect(component.profissionalForm.controls.unidadeAtuacao.value).toBe('USF:7');
    resposta.next(usfs); fixture.detectChanges();
    expect((fixture.nativeElement.querySelector('#unidadeAtuacao') as HTMLSelectElement).value).toBe('USF:7');
  });

  it.each([
    ['USF', 7, 'USF:9', 'USF', 9],
    ['USF', 7, 'CAPS_AD', 'CAPS_AD', null],
    ['CAPS_I', null, 'USF:7', 'USF', 7],
  ])('exibe a rejeição do backend ao tentar transferir %s para %s', (origem, usfId, selecao, destino, esperado) => {
    id = 'usuario-id';
    buscarPorId.mockReturnValue(of({ ...profissional, unidadeAtuacao: origem as string, usfAtuacaoId: usfId as number | null }));
    criar();
    atualizar.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 422,
      error: { message: 'Só é possível salvar usuários para a sua própria unidade.' } })));
    component.profissionalForm.controls.unidadeAtuacao.setValue(selecao as string);
    component.salvarProfissional();
    expect(atualizar).toHaveBeenCalledWith(id, expect.objectContaining({ unidadeAtuacao: destino, usfAtuacaoId: esperado, senha: null }));
    expect(component.erroGeral).toContain('sua própria unidade');
  });

  it('edita dados da própria unidade mantendo a senha opcional', () => {
    id = 'usuario-id'; criar(); component.salvarProfissional();
    expect(atualizar).toHaveBeenCalledWith(id, expect.objectContaining({ unidadeAtuacao: 'USF', usfAtuacaoId: 7, senha: null }));
  });

  it('USF legada precisa de uma unidade específica antes de salvar', () => {
    id = 'usuario-id';
    buscarPorId.mockReturnValue(of({ ...profissional, usfAtuacaoId: null }));
    criar();
    expect(component.profissionalForm.controls.unidadeAtuacao.value).toBeNull();
    component.salvarProfissional();
    expect(atualizar).not.toHaveBeenCalled();
  });
});
