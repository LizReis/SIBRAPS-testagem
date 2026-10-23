import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { of, Subject } from 'rxjs';
import { ProfissionalPayload, ProfissionalService } from '../../services/profissional/profissional-service';
import { UsuarioLogadoService } from '../../services/usuario-logado-service';
import { UnidadeSaude, UnidadeSaudeService } from '../../services/unidade-saude-service';

import { Profissionais } from './profissionais';

describe('Profissionais', () => {
  let component: Profissionais;
  let fixture: ComponentFixture<Profissionais>;
  let listar: ReturnType<typeof vi.fn>;
  let unidades: Subject<UnidadeSaude[]>;
  const centro = { id: 7, cnes: '0012345', nomeUsf: 'USF nome da listagem' };
  const andaia = { id: 8, cnes: '0098765', nomeUsf: 'USF Andaiá' };
  const registro = (nome: string, unidadeAtuacao: string, usfAtuacao = null as ProfissionalPayload['usfAtuacao'],
    tipoUsuario = 'PROFISSIONAL'): ProfissionalPayload => ({
      idPublico: nome, nome, email: `${nome.toLowerCase()}@test.local`, senha: null,
      tipoUsuario, unidadeAtuacao, usfAtuacao,
    });

  beforeEach(async () => {
    listar = vi.fn(() => of([registro('Profissional teste', 'USF', centro)]));
    unidades = new Subject<UnidadeSaude[]>();
    await TestBed.configureTestingModule({
      imports: [Profissionais],
      providers: [
        { provide: Router, useValue: { navigate: vi.fn() } },
        { provide: UsuarioLogadoService, useValue: { obterUsuarioLogado: () => of({ tipoUsuario: 'ADMINISTRADOR' }) } },
        { provide: ProfissionalService, useValue: { listar } },
        { provide: UnidadeSaudeService, useValue: { listarUnidades: () => unidades } },
      ],
    })
    .compileComponents();

    fixture = TestBed.createComponent(Profissionais);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('mostra o nome da USF no mesmo campo da listagem', () => {
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('USF nome da listagem');
  });

  it('carrega nomes dinâmicos e CNES sem oferecer USF genérica', async () => {
    unidades.next([centro, andaia].map(u => ({ ...u, bairro: '', logradouro: '', latitude: '', longitude: '' })));
    await fixture.whenStable();
    const options = Array.from(fixture.nativeElement.querySelectorAll(
      'select[aria-label="Filtrar por unidade"] option')) as HTMLOptionElement[];
    expect(options.map(o => o.textContent?.trim())).toEqual([
      'Todas as unidades', 'CAPS AD', 'CAPS I', 'CAPS II', centro.nomeUsf, andaia.nomeUsf,
    ]);
    expect(options.map(o => o.value)).toEqual(['', 'CAPS_AD', 'CAPS_I', 'CAPS_II', 'USF:0012345', 'USF:0098765']);
  });

  it.each(['CAPS_AD', 'CAPS_I', 'CAPS_II'])('filtra %s sem misturar CAPS e USFs', unidade => {
    component.profissionais = [registro('AD', 'CAPS_AD'), registro('I', 'CAPS_I'),
      registro('II', 'CAPS_II'), registro('Centro', 'USF', centro)];
    component.unidadeSelecionada = unidade;
    expect(component.profissionaisFiltrados.map(p => p.unidadeAtuacao)).toEqual([unidade]);
  });

  it('distingue duas USFs pelo CNES e não pelo nome', () => {
    component.profissionais = [registro('Centro', 'USF', centro),
      registro('Outra', 'USF', { ...andaia, nomeUsf: centro.nomeUsf }), registro('Legado', 'USF')];
    component.unidadeSelecionada = `USF:${centro.cnes}`;
    expect(component.profissionaisFiltrados.map(p => p.nome)).toEqual(['Centro']);
    component.unidadeSelecionada = `USF:${andaia.cnes}`;
    expect(component.profissionaisFiltrados.map(p => p.nome)).toEqual(['Outra']);
    component.unidadeSelecionada = '';
    expect(component.profissionaisFiltrados).toHaveLength(3);
  });

  it('selecionar outra unidade retorna vazio sem ampliar a consulta ou os cards', () => {
    component.profissionais = [registro('Admin', 'USF', centro, 'ADMINISTRADOR'),
      ...Array.from({ length: 4 }, (_, i) => registro(`Prof ${i}`, 'USF', centro)),
      registro('Recepção', 'USF', centro, 'RECEPCAO')];
    for (const unidade of ['CAPS_AD', `USF:${andaia.cnes}`]) {
      component.unidadeSelecionada = unidade;
      expect(component.profissionaisFiltrados).toHaveLength(0);
      expect(component.totalUsuarios).toBe(6);
      expect(component.quantidadePorTipo('ADMINISTRADOR')).toBe(1);
      expect(component.quantidadePorTipo('PROFISSIONAL')).toBe(4);
      expect(component.quantidadePorTipo('RECEPCAO')).toBe(1);
    }
    expect(listar).toHaveBeenCalledExactlyOnceWith();
  });

  it('combina unidade, cargo e busca por nome ou email sem diferenciar maiúsculas', () => {
    const ana = registro('Ana', 'USF', centro);
    ana.email = 'atendimento@test.local';
    component.profissionais = [ana, registro('Ana recepção', 'USF', centro, 'RECEPCAO'),
      registro('Ana fora', 'USF', andaia)];
    component.unidadeSelecionada = `USF:${centro.cnes}`;
    component.tipoUsuarioSelecionado = 'PROFISSIONAL';
    for (const termo of [' ANA ', 'ATENDIMENTO@']) {
      component.termoPesquisa = termo;
      expect(component.profissionaisFiltrados).toEqual([ana]);
    }
  });

  it('mantém paginação e volta à primeira página quando o filtro muda', async () => {
    listar.mockReturnValue(of(Array.from({ length: 12 }, (_, i) => registro(`Pessoa ${i}`, 'USF', centro))));
    component.carregarProfissionais();
    await fixture.whenStable();
    expect(component.profissionaisPaginados).toHaveLength(10);
    component.irParaPagina(2);
    expect(component.profissionaisPaginados).toHaveLength(2);
    expect(component.primeiroRegistro).toBe(11);
    expect(component.ultimoRegistro).toBe(12);
    const input: HTMLInputElement = fixture.nativeElement.querySelector('input[type="search"]');
    input.value = 'Pessoa 11';
    input.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    expect(component.paginaAtual).toBe(1);
    expect(component.profissionaisPaginados.map(p => p.nome)).toEqual(['Pessoa 11']);
  });

  it('informa falha ao carregar o catálogo de USFs', async () => {
    unidades.error(new Error('indisponível'));
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('.alert-erro')?.textContent)
      .toContain('Não foi possível carregar as unidades de saúde.');
  });
});
