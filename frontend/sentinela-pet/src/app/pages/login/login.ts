import { interpretarErroHttp } from '../../services/erro-http';
import { ChangeDetectorRef, Component, inject } from '@angular/core';
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
  private readonly cdr = inject(ChangeDetectorRef);
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

          const resultado = interpretarErroHttp(err, { padrao: 'Não foi possível concluir o login.' });
          this.showError(resultado.tipo === 'AUTHENTICATION_REQUIRED' ? 'E-mail ou senha inválidos.' : resultado.mensagem);
        },
      });
  }

  /**
   * Exibe uma mensagem de erro temporariamente.
   */
  private showError(message: string): void {
    this.errorMessage = message;
    this.cdr.markForCheck();

    setTimeout(() => {
      if (this.errorMessage === message) {
        this.errorMessage = '';
        this.cdr.markForCheck();
      }
    }, this.ERROR_DISPLAY_TIME);
  }
}
