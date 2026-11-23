import { CommonModule } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, EventEmitter, OnDestroy, Output } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { merge, Subscription } from 'rxjs';

import {
  CadastroUnicoService,
  DocumentosCadastroUnico,
  IdentidadeCadastralMinima,
} from '../../services/paciente/cadastro-unico-service';

/**
 * Caminho independente para pessoa JA existente, sem usar o POST legado
 * de novo paciente e sem navegar automaticamente ao prontuario clinico.
 * A confirmacao por checkbox e explicita e ocorre APOS a conferencia.
 */
@Component({
  selector: 'app-cadastro-existente',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  templateUrl: './cadastro-existente.html',
  styleUrl: './cadastro-existente.css',
})
export class CadastroExistente implements OnDestroy {
  @Output() readonly vinculoConfirmado = new EventEmitter<void>();

  readonly documentosForm = new FormGroup({
    cpf: new FormControl('', { nonNullable: true }),
    cns: new FormControl('', { nonNullable: true }),
  });

  buscando = false;
  vinculando = false;
  concluido = false;
  confirmouIdentidade = false;
  identidade: IdentidadeCadastralMinima | null = null;
  erro: string | null = null;

  private documentosConsultados: DocumentosCadastroUnico | null = null;
  private consultaAtual?: Subscription;
  private vinculoAtual?: Subscription;
  private readonly alteracoes: Subscription;

  constructor(private readonly cadastro: CadastroUnicoService) {
    // Dados alterados invalidam a conferencia anterior. Um resultado antigo
    // jamais pode ser confirmado com documentos novos.
    this.alteracoes = merge(
      this.documentosForm.controls.cpf.valueChanges,
      this.documentosForm.controls.cns.valueChanges,
    ).subscribe(() => {
      this.consultaAtual?.unsubscribe();
      this.buscando = false;
      this.identidade = null;
      this.documentosConsultados = null;
      this.confirmouIdentidade = false;
      this.erro = null;
    });
  }

  ngOnDestroy(): void {
    this.alteracoes.unsubscribe();
    this.consultaAtual?.unsubscribe();
    this.vinculoAtual?.unsubscribe();
  }

  conferir(): void {
    if (this.concluido || this.buscando || this.vinculando) return;

    const cpf = this.documentosForm.controls.cpf.value.replace(/\D/g, '');
    const cns = this.documentosForm.controls.cns.value.replace(/\D/g, '');
    this.erro = null;
    this.identidade = null;
    this.confirmouIdentidade = false;
    this.documentosConsultados = null;

    if ((!cpf && !cns) || (cpf && cpf.length !== 11) || (cns && cns.length !== 15)) {
      this.erro = 'Informe um CPF de 11 dígitos ou CNS de 15 dígitos para conferir.';
      return;
    }

    const documentos = {
      ...(cpf ? { cpf } : {}),
      ...(cns ? { cns } : {}),
    };
    this.buscando = true;
    this.consultaAtual = this.cadastro.conferirDocumentos(documentos).subscribe({
      next: dados => {
        this.buscando = false;
        if (!dados.nome || !dados.nomeMae || !dados.dataNascimento) {
          this.erro = 'Cadastro com dados incompletos. Solicite revisão cadastral.';
          return;
        }
        this.identidade = dados;
        this.documentosConsultados = documentos;
      },
      error: (erro: HttpErrorResponse) => {
        this.buscando = false;
        this.erro = erro.status === 404
          ? 'Cadastro não localizado. Para uma pessoa nova, use o formulário abaixo.'
          : erro.status === 422
            ? 'Os documentos não puderam ser conferidos. Revise os dados ou solicite correção cadastral.'
            : 'Não foi possível conferir o cadastro. Tente novamente.';
      },
    });
  }

  confirmarVinculoLocal(): void {
    if (this.concluido || this.vinculando || this.buscando
        || !this.confirmouIdentidade || !this.identidade || !this.documentosConsultados) return;

    const atual = this.documentosForm.getRawValue();
    // Confere novamente se o operador nao alterou os documentos desde a
    // consulta. O servidor faz a mesma verificacao antes do INSERT.
    if ((this.documentosConsultados.cpf ?? '') !== atual.cpf.replace(/\D/g, '')
        || (this.documentosConsultados.cns ?? '') !== atual.cns.replace(/\D/g, '')) {
      this.identidade = null;
      this.documentosConsultados = null;
      this.confirmouIdentidade = false;
      this.erro = 'Os documentos foram alterados. Faça uma nova conferência.';
      return;
    }

    this.vinculando = true;
    this.erro = null;
    this.documentosForm.disable({ emitEvent: false });
    this.vinculoAtual = this.cadastro.confirmarVinculo({
      ...this.documentosConsultados,
      nome: this.identidade.nome,
      nomeMae: this.identidade.nomeMae,
      dataNascimento: this.identidade.dataNascimento,
    }).subscribe({
      next: () => {
        this.vinculando = false;
        this.concluido = true;
        this.vinculoConfirmado.emit();
      },
      error: (erro: HttpErrorResponse) => {
        this.vinculando = false;
        this.documentosForm.enable({ emitEvent: false });
        // Exige conferencia nova apos rejeicao do servidor, inclusive se
        // a identidade mudou entre consulta e tentativa de vincular.
        this.identidade = null;
        this.documentosConsultados = null;
        this.confirmouIdentidade = false;
        this.erro = erro.status === 422 || erro.status === 409 || erro.status === 404
          ? 'Vínculo não realizado. Confirme novamente os dados com a equipe.'
          : 'Não foi possível vincular à unidade. Confira sua conexão e tente a conferência novamente.';
      },
    });
  }
}
