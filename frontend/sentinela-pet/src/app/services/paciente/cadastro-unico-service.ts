import { raizClinica } from '../configuracao-contrato';
import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

/**
 * Contratos de PRE-CONFERENCIA e VINCULACAO locais do cadastro unico.
 *
 * A resposta da conferencia contem apenas dados demograficos minimos,
 * sem identificador global, vinculos, indicadores ou historico clinico.
 * Documentos pessoais sao enviados no corpo POST, NUNCA em path/query.
 */
export interface DocumentosCadastroUnico {
  cpf?: string | null;
  cns?: string | null;
}

export interface IdentidadeCadastralMinima {
  nome: string;
  nomeMae: string;
  dataNascimento: string; // yyyy-MM-dd, como fornecido pelo backend
}

export interface ConfirmacaoCadastroUnico extends DocumentosCadastroUnico {
  tipoAcompanhamento?: string;
  nome: string;
  nomeMae: string;
  dataNascimento: string; // yyyy-MM-dd
}

@Injectable({ providedIn: 'root' })
export class CadastroUnicoService {
  private readonly baseUrl = `${raizClinica}/pacientes/cadastro-unico`;

  constructor(private readonly http: HttpClient) {}

  /** Somente confirma nome, nome da mae e nascimento: nenhuma escrita. */
  conferirDocumentos(dados: DocumentosCadastroUnico): Observable<IdentidadeCadastralMinima> {
    return this.http.post<IdentidadeCadastralMinima>(
      `${this.baseUrl}/conferencia`,
      dados,
    );
  }

  /**
   * A confirmacao humana e obrigatoria. Um HTTP 204 significa somente
   * que o vinculo assistencial LOCAL foi gravado ou ja existia.
   *
   * A operacao NAO cadastra nova identidade, NAO altera referencias do
   * paciente e NAO autoriza navegar automaticamente ao prontuario clinico.
   */
  confirmarVinculo(dados: ConfirmacaoCadastroUnico): Observable<void> {
    return this.http.post<void>(
      `${this.baseUrl}/confirmar-vinculo`,
      dados,
    );
  }
}
