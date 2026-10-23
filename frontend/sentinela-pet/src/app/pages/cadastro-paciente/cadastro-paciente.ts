import { CommonModule } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';

import { UnidadeSaude, UnidadeSaudeService } from '../../services/unidade-saude-service';
import { ProfissionalService, UsuarioReferencia } from '../../services/profissional/profissional-service';

import {
  PacientePayload,
  PacienteService,
  ValidationError,
  StandardError,
} from '../../services/paciente/paciente-service';

enum SexoEnum {
  MASCULINO = 'MASCULINO',
  FEMININO = 'FEMININO',
  INTERSEXO = 'INTERSEXO',
  OUTRO = 'OUTRO',
  NAO_INFORMADO = 'NAO_INFORMADO',
}

enum RacaCorEnum {
  BRANCA = 'BRANCA',
  PRETA = 'PRETA',
  PARDA = 'PARDA',
  AMARELA = 'AMARELA',
  INDIGENA = 'INDIGENA',
  NAO_INFORMADO = 'NAO_INFORMADO',
}

enum TipoAcompanhamento {
  GRUPO_TERAPEUTICO = 'GRUPO_TERAPEUTICO',
  INDIVIDUAL = 'INDIVIDUAL',
  AMBOS = 'AMBOS',
}

enum CapsEnum {
  CAPS_AD = 'CAPS_AD',
  CAPS_I = 'CAPS_I',
  CAPS_II = 'CAPS_II',
}

@Component({
  selector: 'app-cadastro-paciente',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  templateUrl: './cadastro-paciente.html',
  styleUrl: './cadastro-paciente.css',
})
export class CadastroPaciente implements OnInit {
  readonly estados = [
    'AC', 'AL', 'AP', 'AM', 'BA', 'CE', 'DF', 'ES', 'GO', 'MA', 'MT', 'MS',
    'MG', 'PA', 'PB', 'PR', 'PE', 'PI', 'RJ', 'RN', 'RS', 'RO', 'RR', 'SC',
    'SP', 'SE', 'TO',
  ];

  readonly camposIdentificacao = [
    'nome', 'nomeMae', 'dataNascimento', 'sexo', 'racacor', 'situacaoRua',
  ];

  readonly camposDocumentos = [
    'cpf', 'cns', 'telefone', 'usfReferencia', 'capsReferencia',
    'tipoAcompanhamento',
  ];

  readonly camposEndereco = [
    'endereco.cep', 'endereco.estado', 'endereco.cidade', 'endereco.bairro',
    'endereco.logradouro', 'endereco.numero',
  ];
  sexoOptions = Object.values(SexoEnum);
  racaCorOptions = Object.values(RacaCorEnum);
  tipoAcompanhamentoOptions = Object.values(TipoAcompanhamento);
  capsOptions = Object.values(CapsEnum);

  unidadesSaude: UnidadeSaude[] = [];
  usuariosReferencia: UsuarioReferencia[] = [];

  carregandoUnidades = false;
  erroUnidades = false;
  carregandoReferencias = false;
  erroReferencias = false;
  carregandoPaciente = false;
  salvando = false;

  erroGeral: string | null = null;
  errosPorCampo: Record<string, string> = {};

  modoEdicao = false;
  idPublico: string | null = null;

  pacienteForm = new FormGroup({
    nome: new FormControl('', Validators.required),

    nomeMae: new FormControl('', Validators.required),

    dataNascimento: new FormControl('', Validators.required),

    sexo: new FormControl('', Validators.required),

    racacor: new FormControl('', Validators.required),

    cns: new FormControl('', Validators.required),

    cpf: new FormControl('', Validators.required),

    telefone: new FormControl('', Validators.required),

    usfReferencia: new FormControl<UnidadeSaude | null>(null, Validators.required),

    capsReferencia: new FormControl('', Validators.required),

    situacaoRua: new FormControl<boolean | null>(null, Validators.required),

    tipoAcompanhamento: new FormControl('', Validators.required),
    profissionalReferenciaId: new FormControl<string | null>(null),

    endereco: new FormGroup({
      cidade: new FormControl('', Validators.required),

      estado: new FormControl('', Validators.required),

      bairro: new FormControl('', Validators.required),

      logradouro: new FormControl('', Validators.required),

      numero: new FormControl('', Validators.required),

      complemento: new FormControl(''),

      cep: new FormControl('', Validators.required),
    }),
  });

  constructor(
    private router: Router,
    private route: ActivatedRoute,
    private unidadeSaudeService: UnidadeSaudeService,
    private pacienteService: PacienteService,
    private profissionalService: ProfissionalService,
  ) {}

  ngOnInit(): void {
    this.idPublico = this.route.snapshot.paramMap.get('id');
    this.modoEdicao = !!this.idPublico;

    this.carregarUnidadesSaude();
    this.carregarReferencias();
    this.pacienteForm.controls.situacaoRua.valueChanges.subscribe((rua) => {
      this.atualizarValidadoresEndereco(rua === true);
    });
  }

  carregarReferencias(): void {
    this.carregandoReferencias = true;
    this.erroReferencias = false;
    this.profissionalService.listarElegiveisParaReferencia().subscribe({
      next: usuarios => {
        this.usuariosReferencia = usuarios;
        this.carregandoReferencias = false;
      },
      error: () => {
        this.erroReferencias = true;
        this.carregandoReferencias = false;
      },
    });
  }

  carregarUnidadesSaude(): void {
    this.carregandoUnidades = true;
    this.erroUnidades = false;

    this.unidadeSaudeService.listarUnidades().subscribe({
      next: (unidades) => {
        this.unidadesSaude = unidades;
        this.carregandoUnidades = false;

        // Só carrega os dados do paciente depois que as unidades estiverem
        // disponíveis, para conseguir casar o objeto certo no select.
        if (this.modoEdicao && this.idPublico) {
          this.carregarPaciente(this.idPublico);
        }
      },

      error: (erro) => {
        console.error(erro);
        this.erroUnidades = true;
        this.carregandoUnidades = false;
      },
    });
  }

  carregarPaciente(idPublico: string): void {
    this.carregandoPaciente = true;
    this.erroGeral = null;

    this.pacienteService.buscarPorId(idPublico).subscribe({
      next: (paciente) => {
        const unidadeCorrespondente =
          this.unidadesSaude.find((u) => u.id === paciente.usfReferencia?.id) ?? null;

        this.pacienteForm.patchValue({
          nome: paciente.nome,
          nomeMae: paciente.nomeMae,
          dataNascimento: paciente.dataNascimento,
          sexo: paciente.sexo,
          racacor: paciente.racacor,
          cns: paciente.cns,
          cpf: paciente.cpf,
          telefone: paciente.telefone,
          usfReferencia: unidadeCorrespondente,
          capsReferencia: paciente.capsReferencia,
          situacaoRua: paciente.situacaoRua,
          tipoAcompanhamento: paciente.tipoAcompanhamento,
          profissionalReferenciaId: paciente.profissionalReferencia?.idPublico ?? null,
          endereco: {
            cidade: paciente.endereco?.cidade ?? '',
            estado: paciente.endereco?.estado ?? '',
            bairro: paciente.endereco?.bairro ?? '',
            logradouro: paciente.endereco?.logradouro ?? '',
            numero: paciente.endereco?.numero ?? '',
            complemento: paciente.endereco?.complemento ?? '',
            cep: paciente.endereco?.cep ?? '',
          },
        });

        // Reaplica as máscaras visuais em cima dos valores já carregados
        this.aplicarMascaraCpf();
        this.aplicarMascaraTelefone();
        this.aplicarMascaraCep();

        this.carregandoPaciente = false;
      },

      error: (erro) => {
        console.error(erro);
        this.erroGeral = 'Não foi possível carregar os dados do paciente.';
        this.carregandoPaciente = false;
      },
    });
  }

  salvarPaciente(): void {
    this.erroGeral = null;
    this.errosPorCampo = {};

    if (this.pacienteForm.invalid) {
      this.pacienteForm.markAllAsTouched();
      setTimeout(() => document.querySelector<HTMLElement>('form .ng-invalid:not(form)')?.focus());
      return;
    }

    this.salvando = true;

    const formValue = this.pacienteForm.getRawValue();

    const paciente: PacientePayload = {
      nome: formValue.nome?.trim() ?? '',

      nomeMae: formValue.nomeMae?.trim() ?? '',

      dataNascimento: formValue.dataNascimento ?? '',

      sexo: formValue.sexo ?? '',

      racacor: formValue.racacor ?? '',

      cns: this.somenteNumeros(formValue.cns),

      cpf: this.somenteNumeros(formValue.cpf),

      telefone: this.somenteNumeros(formValue.telefone),

      usfReferencia: formValue.usfReferencia!,

      capsReferencia: formValue.capsReferencia ?? '',

      situacaoRua: formValue.situacaoRua ?? false,

      tipoAcompanhamento: formValue.tipoAcompanhamento ?? '',
      profissionalReferenciaId: formValue.profissionalReferenciaId || null,
      endereco: (formValue.situacaoRua ? null : {
        cidade: formValue.endereco?.cidade?.trim() ?? '',

        estado: formValue.endereco?.estado?.trim().toUpperCase() ?? '',

        bairro: formValue.endereco?.bairro?.trim() ?? '',

        logradouro: formValue.endereco?.logradouro?.trim() ?? '',

        numero: formValue.endereco?.numero?.trim() ?? '',

        complemento: formValue.endereco?.complemento?.trim() ?? '',

        cep: this.somenteNumeros(formValue.endereco?.cep),
      }),
    };

    const request$ = this.modoEdicao
      ? this.pacienteService.atualizarPaciente(this.idPublico!, paciente)
      : this.pacienteService.cadastrarPaciente(paciente);

    request$.subscribe({
      next: () => {
        this.salvando = false;

        if (this.modoEdicao) {
          this.router.navigate(['/pacientes/detalhes', this.idPublico]);
        } else {
          this.router.navigate(['/pacientes']);
        }
      },

      error: (erro: HttpErrorResponse) => {
        this.salvando = false;
        this.tratarErro(erro);
      },
    });
  }

  atualizarValidadoresEndereco(situacaoRua: boolean): void {
    const endereco = this.pacienteForm.controls.endereco;

    ['cidade', 'estado', 'bairro', 'logradouro', 'numero', 'cep'].forEach((nome) => {
      const controle = endereco.get(nome);

      if (situacaoRua) {
        controle?.clearValidators();
        controle?.setErrors(null);
        controle?.markAsUntouched();
      } else {
        controle?.setValidators(Validators.required);
      }

      controle?.updateValueAndValidity({ emitEvent: false });
    });
  }

  get progresso(): number {
    const camposObrigatorios = [
      ...this.camposIdentificacao,
      ...this.camposDocumentos,
      ...(this.pacienteForm.controls.situacaoRua.value === true
        ? []
        : this.camposEndereco),
    ];
    const preenchidos = camposObrigatorios.filter((campo) =>
      this.controlePreenchido(campo));

    return Math.round((preenchidos.length / camposObrigatorios.length) * 100);
  }

  estadoSecao(campos: string[]): string {
    const preenchidos = campos.filter((campo) => this.controlePreenchido(campo)).length;

    if (preenchidos === 0) return 'Incompleto';
    if (preenchidos === campos.length) return 'Concluído';
    return 'Em preenchimento';
  }

  get estadoEndereco(): string {
    return this.pacienteForm.controls.situacaoRua.value === true
      ? 'Concluído'
      : this.estadoSecao(this.camposEndereco);
  }

  private controlePreenchido(caminho: string): boolean {
    const control = this.pacienteForm.get(caminho);

    if (!control || control.invalid) return false;

    const valor = control.value;
    if (typeof valor === 'boolean') return true;
    if (typeof valor === 'string') return valor.trim().length > 0;
    return valor !== null && valor !== undefined;
  }

  private tratarErro(erro: HttpErrorResponse): void {
    if (!erro.error) {
      this.erroGeral = 'Não foi possível conectar ao servidor. Tente novamente.';
      return;
    }

    if (erro.status === 422 && Array.isArray(erro.error.errors)) {
      const validationError = erro.error as ValidationError;

      for (const campo of validationError.errors) {
        this.errosPorCampo[campo.fieldName] = campo.message;
      }

      this.erroGeral = validationError.message;
      this.marcarCamposComoTocados();
      return;
    }

    if (erro.status === 409) {
      const standardError = erro.error as StandardError;
      this.erroGeral = standardError.message ?? 'Já existe um paciente com esses dados.';
      return;
    }

    if (erro.error.message) {
      const standardError = erro.error as StandardError;
      this.erroGeral = standardError.message;
      return;
    }

    this.erroGeral = this.modoEdicao
      ? 'Não foi possível atualizar o paciente.'
      : 'Não foi possível salvar o paciente.';
  }

  private marcarCamposComoTocados(): void {
    Object.keys(this.errosPorCampo).forEach((campo) => {
      const control = this.pacienteForm.get(campo);
      control?.markAsTouched();
    });
  }

  cancelar(): void {
    if (this.modoEdicao && this.idPublico) {
      this.router.navigate(['/pacientes/detalhes', this.idPublico]);
    } else {
      this.router.navigate(['/pacientes']);
    }
  }

  somenteNumerosCampo(campo: string, limite?: number): void {
    const control = this.pacienteForm.get(campo);

    if (!control) return;

    let valor = this.somenteNumeros(control.value);

    if (limite) {
      valor = valor.slice(0, limite);
    }

    control.setValue(valor, { emitEvent: false });
  }

  aplicarMascaraCpf(): void {
    const control = this.pacienteForm.get('cpf');

    if (!control) return;

    let valor = this.somenteNumeros(control.value).slice(0, 11);

    valor = valor.replace(/^(\d{3})(\d)/, '$1.$2');
    valor = valor.replace(/^(\d{3})\.(\d{3})(\d)/, '$1.$2.$3');
    valor = valor.replace(/^(\d{3})\.(\d{3})\.(\d{3})(\d)/, '$1.$2.$3-$4');

    control.setValue(valor, { emitEvent: false });
  }

  aplicarMascaraTelefone(): void {
    const control = this.pacienteForm.get('telefone');

    if (!control) return;

    let valor = this.somenteNumeros(control.value).slice(0, 11);

    if (valor.length <= 10) {
      valor = valor.replace(/^(\d{2})(\d)/, '($1) $2');
      valor = valor.replace(/(\d{4})(\d)/, '$1-$2');
    } else {
      valor = valor.replace(/^(\d{2})(\d)/, '($1) $2');
      valor = valor.replace(/(\d{5})(\d)/, '$1-$2');
    }

    control.setValue(valor, { emitEvent: false });
  }

  aplicarMascaraCep(): void {
    const control = this.pacienteForm.get('endereco.cep');

    if (!control) return;

    let valor = this.somenteNumeros(control.value).slice(0, 8);

    valor = valor.replace(/^(\d{5})(\d)/, '$1-$2');

    control.setValue(valor, { emitEvent: false });
  }

  campoInvalido(campo: string): boolean {
    const control = this.pacienteForm.get(campo);

    return !!control && control.invalid && (control.touched || control.dirty);
  }

  mensagemErro(campo: string, mensagemPadrao: string): string {
    return this.errosPorCampo[campo] ?? mensagemPadrao;
  }

  labelEnum(valor: string): string {
    return valor
      .replaceAll('_', ' ')
      .toLowerCase()
      .replace(/\b\w/g, (letra) => letra.toUpperCase());
  }

  private somenteNumeros(valor: unknown): string {
    return String(valor ?? '').replace(/\D/g, '');
  }
}
