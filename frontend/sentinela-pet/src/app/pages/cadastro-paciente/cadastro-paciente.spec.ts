import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router } from '@angular/router';
import { of } from 'rxjs';
import { Validators } from '@angular/forms';

import { PacienteService } from '../../services/paciente/paciente-service';
import { UnidadeSaudeService } from '../../services/unidade-saude-service';
import { ProfissionalService } from '../../services/profissional/profissional-service';
import { CadastroPaciente } from './cadastro-paciente';

describe('CadastroPaciente', () => {
  let component: CadastroPaciente;
  let fixture: ComponentFixture<CadastroPaciente>;
  const unidade = { id: 1, cnes: '1', nomeUsf: 'Unidade', bairro: '', logradouro: '', latitude: '', longitude: '' };
  const cadastrarPaciente = vi.fn((_paciente: any) => of({}));
  const atualizarPaciente = vi.fn((_id: string, _paciente: any) => of({}));
  const navigate = vi.fn();

  beforeEach(async () => {
    cadastrarPaciente.mockClear();
    atualizarPaciente.mockClear();
    navigate.mockClear();

    await TestBed.configureTestingModule({
      imports: [CadastroPaciente],
      providers: [
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: { get: () => null } } } },
        { provide: Router, useValue: { navigate } },
        { provide: UnidadeSaudeService, useValue: { listarUnidades: () => of([unidade]) } },
        { provide: ProfissionalService, useValue: { listarElegiveisParaReferencia: () => of([
          { idPublico: 'admin-id', nome: 'Administrador', unidadeAtuacao: 'CAPS_I' },
          { idPublico: 'prof-id', nome: 'Profissional', unidadeAtuacao: 'CAPS_II' },
        ]) } },
        {
          provide: PacienteService,
          useValue: { cadastrarPaciente, atualizarPaciente, buscarPorId: vi.fn() },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(CadastroPaciente);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  function preencherFormulario(situacaoRua = false): void {
    component.pacienteForm.patchValue({
      nome: 'Pessoa Teste',
      nomeMae: 'Mãe Teste',
      dataNascimento: '2000-01-01',
      sexo: 'FEMININO',
      racacor: 'PARDA',
      cns: '123456789012345',
      cpf: '529.982.247-25',
      telefone: '(75) 99999-9999',
      usfReferencia: unidade,
      capsReferencia: 'CAPS_I',
      situacaoRua,
      tipoAcompanhamento: 'INDIVIDUAL',
      endereco: {
        cep: '44430-622',
        estado: 'BA',
        cidade: 'Santo Antônio de Jesus',
        bairro: 'Cajueiro',
        logradouro: 'Avenida Carlos Amaral',
        numero: '2020',
        complemento: 'Casa',
      },
    });
  }

  it('sincroniza os inputs explícitos com seus FormControls', () => {
    const nome = fixture.nativeElement.querySelector('#nome') as HTMLInputElement;
    const cidade = fixture.nativeElement.querySelector('#cidade') as HTMLInputElement;
    nome.value = 'Pessoa digitada';
    nome.dispatchEvent(new Event('input'));
    cidade.value = 'Santo Antônio de Jesus';
    cidade.dispatchEvent(new Event('input'));

    expect(component.pacienteForm.get('nome')?.value).toBe('Pessoa digitada');
    expect(component.pacienteForm.get('endereco.cidade')?.value).toBe('Santo Antônio de Jesus');
  });

  it('não chama a API quando o formulário está inválido', () => {
    expect(component.progresso).toBe(0);
    component.salvarPaciente();
    expect(cadastrarPaciente).not.toHaveBeenCalled();
  });

  it('mantém o fluxo de atualização no modo edição', () => {
    component.modoEdicao = true;
    component.idPublico = 'paciente-id';
    preencherFormulario(false);
    component.salvarPaciente();

    expect(atualizarPaciente).toHaveBeenCalledOnce();
    expect(atualizarPaciente.mock.calls[0][0]).toBe('paciente-id');
    expect(navigate).toHaveBeenCalledWith(['/pacientes/detalhes', 'paciente-id']);
  });

  it('envia o formulário válido por POST e navega para pacientes', () => {
    preencherFormulario(false);
    component.salvarPaciente();

    expect(cadastrarPaciente).toHaveBeenCalledOnce();
    expect(cadastrarPaciente.mock.calls[0][0].endereco?.cidade).toBe('Santo Antônio de Jesus');
    expect(navigate).toHaveBeenCalledWith(['/pacientes']);
  });

  it('considera Não em situação de rua preenchido e chega a 100%', () => {
    preencherFormulario(false);
    expect(component.progresso).toBe(100);
    expect(component.estadoSecao(component.camposIdentificacao)).toBe('Concluído');
    expect(component.estadoEndereco).toBe('Concluído');
  });

  it('mantém referência opcional fora do progresso e envia null quando não selecionada', () => {
    preencherFormulario(false);
    expect(component.pacienteForm.controls.profissionalReferenciaId.hasValidator(Validators.required)).toBe(false);
    expect(component.progresso).toBe(100);
    component.salvarPaciente();
    expect(cadastrarPaciente.mock.calls[0][0].profissionalReferenciaId).toBeNull();
  });

  it('exibe usuários elegíveis no select sem marcar o campo como obrigatório', () => {
    fixture.detectChanges();
    const label = fixture.nativeElement.querySelector('label[for="profissionalReferenciaId"]');
    const opcoes = Array.from(label.querySelectorAll('option')).map((o: any) => o.textContent.trim());
    expect(label.textContent).toContain('Profissional de referência (opcional)');
    expect(opcoes).toEqual([
      'Nenhum profissional de referência', 'Administrador', 'Profissional',
    ]);
  });

  it('remove e restaura os validadores obrigatórios de endereço', () => {
    component.pacienteForm.controls.situacaoRua.setValue(true);
    expect(component.pacienteForm.get('endereco.cep')?.hasError('required')).toBe(false);
    expect(component.estadoEndereco).toBe('Concluído');

    component.pacienteForm.controls.situacaoRua.setValue(false);
    expect(component.pacienteForm.get('endereco.cep')?.hasError('required')).toBe(true);
  });

  it('permite 100% e payload sem endereço para paciente em situação de rua', () => {
    preencherFormulario(true);
    component.pacienteForm.controls.endereco.reset();
    expect(component.progresso).toBe(100);

    component.salvarPaciente();
    expect(cadastrarPaciente.mock.calls[0][0].endereco).toBeNull();
  });
});