import { CommonModule } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectorRef, Component, OnInit } from '@angular/core';
import {
  AbstractControl,
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { UnidadeSaudeService } from '../../services/unidade-saude-service';
import { UsuarioLogadoService } from '../../services/usuario-logado-service';

import {
  ProfissionalPayload,
  ProfissionalService,
  ValidationError,
  StandardError,
} from '../../services/profissional/profissional-service';

enum TipoUsuario {
  ADMINISTRADOR = 'ADMINISTRADOR',
  PROFISSIONAL = 'PROFISSIONAL',
  RECEPCAO = 'RECEPCAO',
}

enum UnidadeAtuacao {
  USF = 'USF',
  CAPS_AD = 'CAPS_AD',
  CAPS_I = 'CAPS_I',
  CAPS_II = 'CAPS_II',
}

@Component({
  selector: 'app-cadastro-profissional',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  templateUrl: './cadastro-profissional.html',
  styleUrl: './cadastro-profissional.css',
})
export class CadastroProfissional implements OnInit {
  tipoUsuarioOptions = Object.values(TipoUsuario);
  unidadeAtuacaoOptions: { valor: string; label: string }[] = [
    { valor: UnidadeAtuacao.CAPS_AD, label: 'CAPS AD' },
    { valor: UnidadeAtuacao.CAPS_I, label: 'CAPS I' },
    { valor: UnidadeAtuacao.CAPS_II, label: 'CAPS II' },
  ];

  salvando = false;
  carregando = false;

  erroGeral: string | null = null;
  errosPorCampo: Record<string, string> = {};

  modoEdicao = false;
  idPublico: string | null = null;
  mostrarSenha = false;
  mostrarConfirmacaoSenha = false;

  profissionalForm = new FormGroup(
    {
      nome: new FormControl('', Validators.required),
      email: new FormControl('', [Validators.required, Validators.email]),
      senha: new FormControl('', [Validators.required, Validators.minLength(6)]),
      confirmarSenha: new FormControl('', Validators.required),
      tipoUsuario: new FormControl<string | null>(null, Validators.required),
      unidadeAtuacao: new FormControl<string | null>(null, Validators.required),
    },
    {
      validators: CadastroProfissional.validarSenhas,
    },
  );

  constructor(
    private router: Router,
    private route: ActivatedRoute,
    private profissionalService: ProfissionalService,
    private unidadeSaudeService: UnidadeSaudeService,
    private changeDetector: ChangeDetectorRef,
    private usuarioLogadoService: UsuarioLogadoService,
  ) {}

  ngOnInit(): void {
    this.idPublico = this.route.snapshot.paramMap.get('id');
    this.modoEdicao = !!this.idPublico;
    if (!this.modoEdicao) {
      this.unidadeAtuacaoOptions = [];
      this.carregando = true;
      this.usuarioLogadoService.obterUsuarioLogado().subscribe({
        next: (usuario) => {
          const usf = usuario.unidadeAtuacao === UnidadeAtuacao.USF;
          if (!usuario.unidadeAtuacao || (usf && !usuario.usfAtuacaoId)) {
            this.erroGeral = 'A unidade de atuação do usuário logado ainda não foi configurada.';
          } else {
            const valor = usf ? `USF:${usuario.usfAtuacaoId}` : usuario.unidadeAtuacao;
            this.unidadeAtuacaoOptions = [{ valor, label: usf
              ? usuario.usfAtuacao?.nomeUsf ?? 'USF da sua unidade' : this.labelEnum(valor).toUpperCase() }];
            this.profissionalForm.controls.unidadeAtuacao.setValue(valor);
            this.profissionalForm.controls.unidadeAtuacao.disable();
          }
          this.carregando = false;
          this.changeDetector.markForCheck();
        },
        error: () => {
          this.erroGeral = 'Não foi possível identificar a unidade do usuário logado.';
          this.carregando = false;
          this.changeDetector.markForCheck();
        },
      });
      return;
    }
    this.unidadeSaudeService.listarUnidades().subscribe({
      next: (unidades) => {
        this.unidadeAtuacaoOptions = [
          ...this.unidadeAtuacaoOptions,
          ...unidades.map((usf) => ({ valor: `USF:${usf.id}`, label: usf.nomeUsf })),
        ];
        this.changeDetector.markForCheck();
      },
      error: () => {
        this.erroGeral = 'Não foi possível carregar as USFs. Tente novamente.';
        this.changeDetector.markForCheck();
      },
    });

    if (this.modoEdicao) {
      // Na edição, a senha é opcional — só valida se o usuário preencher algo
      this.profissionalForm.get('senha')?.setValidators([Validators.minLength(6)]);
      this.profissionalForm.get('confirmarSenha')?.clearValidators();
      this.profissionalForm.get('senha')?.updateValueAndValidity();
      this.profissionalForm.get('confirmarSenha')?.updateValueAndValidity();

      this.carregarProfissional(this.idPublico!);
    }
  }

  carregarProfissional(idPublico: string): void {
    this.carregando = true;

    this.profissionalService.buscarPorId(idPublico).subscribe({
      next: (profissional) => {
        this.profissionalForm.patchValue({
          nome: profissional.nome,
          email: profissional.email,
          tipoUsuario: profissional.tipoUsuario,
          unidadeAtuacao: profissional.unidadeAtuacao === UnidadeAtuacao.USF
            ? profissional.usfAtuacaoId != null ? `USF:${profissional.usfAtuacaoId}` : null
            : profissional.unidadeAtuacao,
        });
        this.carregando = false;
        this.changeDetector.markForCheck();
      },
      error: (erro) => {
        console.error('Erro ao carregar profissional', erro);
        this.erroGeral = 'Não foi possível carregar os dados do profissional.';
        this.carregando = false;
        this.changeDetector.markForCheck();
      },
    });
  }

  salvarProfissional(): void {
    this.erroGeral = null;
    this.errosPorCampo = {};

    if (this.profissionalForm.invalid) {
      this.profissionalForm.markAllAsTouched();
      return;
    }

    this.salvando = true;

    const form = this.profissionalForm.getRawValue();

    const senhaPreenchida = form.senha && form.senha.trim().length > 0;

    const profissional: ProfissionalPayload = {
      nome: form.nome?.trim() ?? '',
      email: form.email?.trim().toLowerCase() ?? '',
      senha: senhaPreenchida ? form.senha!.trim() : null,
      tipoUsuario: form.tipoUsuario ?? '',
      unidadeAtuacao: form.unidadeAtuacao?.startsWith('USF:') ? UnidadeAtuacao.USF : form.unidadeAtuacao ?? '',
      usfAtuacaoId: form.unidadeAtuacao?.startsWith('USF:') ? Number(form.unidadeAtuacao.substring(4)) : null,
    };

    const request$ = this.modoEdicao
      ? this.profissionalService.atualizar(this.idPublico!, profissional)
      : this.profissionalService.cadastrar(profissional);

    request$.subscribe({
      next: () => {
        this.salvando = false;

        if (this.modoEdicao) {
          this.router.navigate(['/profissionais/detalhes', this.idPublico]);
        } else {
          this.router.navigate(['/profissionais']);
        }
      },
      error: (erro: HttpErrorResponse) => {
        this.salvando = false;
        this.tratarErro(erro);
      },
    });
  }

  private tratarErro(erro: HttpErrorResponse): void {
    if (!erro.error) {
      this.erroGeral = 'Não foi possível conectar ao servidor. Tente novamente.';
      return;
    }

    if (erro.status === 422 && Array.isArray(erro.error.errors)) {
      const validationError = erro.error as ValidationError;

      validationError.errors.forEach((campo) => {
        this.errosPorCampo[campo.fieldName] = campo.message;
      });

      this.erroGeral = validationError.message;
      this.marcarCamposComoTocados();
      return;
    }

    if (erro.status === 409) {
      const standardError = erro.error as StandardError;
      this.erroGeral = standardError.message ?? 'Já existe um profissional com esses dados.';
      return;
    }

    if (erro.error.message) {
      const standardError = erro.error as StandardError;
      this.erroGeral = standardError.message;
      return;
    }

    this.erroGeral = this.modoEdicao
      ? 'Não foi possível atualizar o profissional.'
      : 'Não foi possível cadastrar o profissional.';
  }

  private marcarCamposComoTocados(): void {
    Object.keys(this.errosPorCampo).forEach((campo) => {
      this.profissionalForm.get(campo)?.markAsTouched();
    });
  }

  cancelar(): void {
    if (this.modoEdicao && this.idPublico) {
      this.router.navigate(['/profissionais/detalhes', this.idPublico]);
    } else {
      this.router.navigate(['/profissionais']);
    }
  }

  alternarVisibilidadeSenha(): void {
    this.mostrarSenha = !this.mostrarSenha;
  }

  alternarVisibilidadeConfirmacao(): void {
    this.mostrarConfirmacaoSenha = !this.mostrarConfirmacaoSenha;
  }

  campoInvalido(campo: string): boolean {
    const control = this.profissionalForm.get(campo);
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

  static validarSenhas(form: AbstractControl): ValidationErrors | null {
    const senha = form.get('senha')?.value;
    const confirmarSenha = form.get('confirmarSenha')?.value;

    // Em edição, deixar ambos vazios é válido (mantém a senha atual)
    if (!senha && !confirmarSenha) {
      return null;
    }

    if (senha !== confirmarSenha) {
      return { senhasDiferentes: true };
    }

    return null;
  }
}
