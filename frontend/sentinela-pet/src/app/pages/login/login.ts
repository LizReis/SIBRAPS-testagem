import { Component, inject } from '@angular/core';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { finalize, timeout } from 'rxjs/operators';
import { LoginService } from '../../services/login/login-service';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [ReactiveFormsModule],
  templateUrl: './login.html',
  styleUrl: './login.css',
})
export class Login {
  private fb = inject(FormBuilder);
  private loginService = inject(LoginService);
  private router = inject(Router);

  errorMessage = '';
  isLoading = false;

  private readonly REQUEST_TIMEOUT = 7000;
  private readonly ERROR_DISPLAY_TIME = 4000;

  loginForm: FormGroup = this.fb.group({
    email: ['', [Validators.required, Validators.email]],
    password: ['', [Validators.required, Validators.minLength(4)]],
  });

  onSubmit(): void {
    if (this.isLoading) {
      return;
    }

    if (this.loginForm.invalid) {
      this.loginForm.markAllAsTouched();
      this.showError('Por favor, informe um e-mail e uma senha válidos.');
      return;
    }

    this.isLoading = true;
    this.errorMessage = '';

    // Impede alterações durante a autenticação
    this.loginForm.disable();

    const form = this.loginForm.getRawValue();

    const loginRequest = {
      email: form.email,
      senha: form.password,
    };

    this.loginService
      .logar(loginRequest)
      .pipe(
        timeout(this.REQUEST_TIMEOUT),
        finalize(() => {
          this.isLoading = false;
          this.loginForm.enable();
        }),
      )
      .subscribe({
        next: (response) => {
          this.loginService.addToken(response.token);
          this.router.navigate(['/inicio']);
        },

        error: (err) => {
          if (err.name === 'TimeoutError') {
            this.showError(
              'O servidor demorou para responder. Tente novamente em alguns instantes.',
            );
            return;
          }

          switch (err.status) {
            case 401:
            case 403:
              this.showError('E-mail ou senha inválidos.');
              break;

            case 0:
              this.showError(
                'Não foi possível conectar ao servidor. Verifique sua conexão ou tente novamente.',
              );
              break;

            case 500:
              this.showError(
                'Ocorreu um erro interno no servidor. Tente novamente em alguns instantes.',
              );
              break;

            default:
              this.showError('Ocorreu um erro inesperado durante o login.');
          }
        },
      });
  }

  /**
   * Exibe uma mensagem de erro temporariamente.
   */
  private showError(message: string): void {
    this.errorMessage = message;

    setTimeout(() => {
      if (this.errorMessage === message) {
        this.errorMessage = '';
      }
    }, this.ERROR_DISPLAY_TIME);
  }
}
